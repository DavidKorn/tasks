/**
 * Copyright (c) 2012 Todoroo Inc
 *
 * See the file "LICENSE" for the full license governing this code.
 */
package com.todoroo.astrid.subtasks;

import com.todoroo.andlib.data.Property.IntegerProperty;
import com.todoroo.andlib.data.Property.LongProperty;
import com.todoroo.andlib.service.DependencyInjectionService;
import com.todoroo.astrid.core.PluginServices;
import com.todoroo.astrid.data.Metadata;
import com.todoroo.astrid.data.Task;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

abstract public class OrderedMetadataListUpdater<LIST> {

    public OrderedMetadataListUpdater() {
        DependencyInjectionService.getInstance().inject(this);
    }

    public interface OrderedListIterator {
        public void processTask(long taskId, Metadata metadata);
    }

    // --- abstract and empty

    abstract protected Metadata getTaskMetadata(long taskId);

    abstract protected IntegerProperty indentProperty();

    abstract protected LongProperty orderProperty();

    abstract protected LongProperty parentProperty();

    abstract protected void iterateThroughList(LIST list, OrderedListIterator iterator);

    abstract protected Metadata createEmptyMetadata(LIST list, long taskId);

    protected void beforeIndent(LIST list) {
        //
    }

    protected void onMovedOrIndented(Metadata metadata) {
        //
    }

    // --- task indenting

    /**
     * Indent a task and all its children
     */
    public void indent(final LIST list, final long targetTaskId, final int delta) {
        if(list == null) {
            return;
        }

        beforeIndent(list);

        final AtomicInteger targetTaskIndent = new AtomicInteger(-1);
        final AtomicInteger previousIndent = new AtomicInteger(-1);
        final AtomicLong previousTask = new AtomicLong(Task.NO_ID);
        final AtomicLong globalOrder = new AtomicLong(-1);

        iterateThroughList(list, new OrderedListIterator() {
            @Override
            public void processTask(long taskId, Metadata metadata) {
                if(!metadata.isSaved()) {
                    metadata = createEmptyMetadata(list, taskId);
                }
                int indent = metadata.containsNonNullValue(indentProperty()) ?
                        metadata.getValue(indentProperty()) : 0;

                long order = globalOrder.incrementAndGet();
                metadata.setValue(orderProperty(), order);

                if(targetTaskId == taskId) {
                    // if indenting is warranted, indent me and my children
                    if(indent + delta <= previousIndent.get() + 1 && indent + delta >= 0) {
                        targetTaskIndent.set(indent);
                        metadata.setValue(indentProperty(), indent + delta);

                        if(parentProperty() != null) {
                            long newParent = computeNewParent(list,
                                    taskId, indent + delta - 1);
                            if (newParent == taskId) {
                                metadata.setValue(parentProperty(), Task.NO_ID);
                            } else {
                                metadata.setValue(parentProperty(), newParent);
                            }
                        }
                        saveAndUpdateModifiedDate(metadata);
                    }
                } else if(targetTaskIndent.get() > -1) {
                    // found first task that is not beneath target
                    if(indent <= targetTaskIndent.get()) {
                        targetTaskIndent.set(-1);
                    } else {
                        metadata.setValue(indentProperty(), indent + delta);
                        saveAndUpdateModifiedDate(metadata);
                    }
                } else {
                    previousIndent.set(indent);
                    previousTask.set(taskId);
                }

                if(!metadata.isSaved()) {
                    saveAndUpdateModifiedDate(metadata);
                }
            }

        });
        onMovedOrIndented(getTaskMetadata(targetTaskId));
    }

    /**
     * Helper function to iterate through a list and compute a new parent for the target task
     * based on the target parent's indent
     */
    private long computeNewParent(LIST list, long targetTaskId, int targetParentIndent) {
        final AtomicInteger desiredParentIndent = new AtomicInteger(targetParentIndent);
        final AtomicLong targetTask = new AtomicLong(targetTaskId);
        final AtomicLong lastPotentialParent = new AtomicLong(Task.NO_ID);
        final AtomicBoolean computedParent = new AtomicBoolean(false);

        iterateThroughList(list, new OrderedListIterator() {
            @Override
            public void processTask(long taskId, Metadata metadata) {
                if (targetTask.get() == taskId) {
                    computedParent.set(true);
                }

                int indent = metadata.getValue(indentProperty());
                if (!computedParent.get() && indent == desiredParentIndent.get()) {
                    lastPotentialParent.set(taskId);
                }
            }
        });

        if (lastPotentialParent.get() == Task.NO_ID) {
            return Task.NO_ID;
        }
        return lastPotentialParent.get();
    }

    // --- task moving

    /**
     * Move a task and all its children to the position right above
     * taskIdToMoveto. Will change the indent level to match taskIdToMoveTo.
     */
    public void moveTo(LIST list, final long targetTaskId,
            final long moveBeforeTaskId) {
        if(list == null) {
            return;
        }

        Node root = buildTreeModel(list);
        Node target = findNode(root, targetTaskId);

        if(target != null && target.parent != null) {
            if(moveBeforeTaskId == -1) {
                target.parent.children.remove(target);
                root.children.add(target);
                target.parent = root;
            } else {
                Node sibling = findNode(root, moveBeforeTaskId);
                if(sibling != null && !ancestorOf(target, sibling)) {
                    int index = sibling.parent.children.indexOf(sibling);

                    if(target.parent == sibling.parent &&
                            target.parent.children.indexOf(target) < index) {
                        index--;
                    }

                    target.parent.children.remove(target);
                    sibling.parent.children.add(index, target);
                    target.parent = sibling.parent;
                }
            }
        }

        traverseTreeAndWriteValues(list, root, new AtomicLong(0), -1);
        onMovedOrIndented(getTaskMetadata(targetTaskId));
    }

    private boolean ancestorOf(Node ancestor, Node descendant) {
        if(descendant.parent == ancestor) {
            return true;
        }
        if(descendant.parent == null) {
            return false;
        }
        return ancestorOf(ancestor, descendant.parent);
    }

    protected static class Node {
        public final long taskId;
        public Node parent;
        public final ArrayList<Node> children = new ArrayList<>();

        public Node(long taskId, Node parent) {
            this.taskId = taskId;
            this.parent = parent;
        }
    }

    protected void traverseTreeAndWriteValues(LIST list, Node node, AtomicLong order, int indent) {
        if(node.taskId != Task.NO_ID) {
            Metadata metadata = getTaskMetadata(node.taskId);
            if(metadata == null) {
                metadata = createEmptyMetadata(list, node.taskId);
            }
            metadata.setValue(orderProperty(), order.getAndIncrement());
            metadata.setValue(indentProperty(), indent);
            boolean parentChanged = false;
            if(parentProperty() != null && metadata.getValue(parentProperty()) !=
                    node.parent.taskId) {
                parentChanged = true;
                metadata.setValue(parentProperty(), node.parent.taskId);
            }
            saveAndUpdateModifiedDate(metadata);
            if(parentChanged) {
                onMovedOrIndented(metadata);
            }
        }

        for(Node child : node.children) {
            traverseTreeAndWriteValues(list, child, order, indent + 1);
        }
    }

    protected Node findNode(Node node, long taskId) {
        if(node.taskId == taskId) {
            return node;
        }
        for(Node child : node.children) {
            Node found = findNode(child, taskId);
            if(found != null) {
                return found;
            }
        }
        return null;
    }

    protected Node buildTreeModel(LIST list) {
        final Node root = new Node(Task.NO_ID, null);
        final AtomicInteger previoustIndent = new AtomicInteger(-1);
        final AtomicReference<Node> currentNode = new AtomicReference<>(root);

        iterateThroughList(list, new OrderedListIterator() {
            @Override
            public void processTask(long taskId, Metadata metadata) {
                int indent = metadata.getValue(indentProperty());

                int previousIndentValue = previoustIndent.get();
                if(indent == previousIndentValue) { // sibling
                    Node parent = currentNode.get().parent;
                    currentNode.set(new Node(taskId, parent));
                    parent.children.add(currentNode.get());
                } else if(indent > previousIndentValue) { // child
                    Node parent = currentNode.get();
                    currentNode.set(new Node(taskId, parent));
                    parent.children.add(currentNode.get());
                } else { // in a different tree
                    Node node = currentNode.get().parent;
                    for(int i = indent; i < previousIndentValue; i++) {
                        node = node.parent;
                        if(node == null) {
                            node = root;
                            break;
                        }
                    }
                    currentNode.set(new Node(taskId, node));
                    node.children.add(currentNode.get());
                }

                previoustIndent.set(indent);
            }
        });
        return root;
    }

    protected void saveAndUpdateModifiedDate(Metadata metadata) {
        if(metadata.getSetValues().size() == 0) {
            return;
        }
        PluginServices.getMetadataService().save(metadata);
    }

    // --- task cascading operations

    public interface OrderedListNodeVisitor {
        public void visitNode(Node node);
    }

    /**
     * Apply an operation only to the children of the task
     */
    public void applyToChildren(LIST list, long targetTaskId,
            OrderedListNodeVisitor visitor) {

        Node root = buildTreeModel(list);
        Node target = findNode(root, targetTaskId);

        if(target != null) {
            for (Node child : target.children) {
                applyVisitor(child, visitor);
            }
        }
    }

    private void applyVisitor(Node node, OrderedListNodeVisitor visitor) {
        visitor.visitNode(node);
        for(Node child : node.children) {
            applyVisitor(child, visitor);
        }
    }

    /**
     * Removes a task from the order hierarchy and un-indent children
     */
    public void onDeleteTask(LIST list, final long targetTaskId) {
        if(list == null) {
            return;
        }

        Node root = buildTreeModel(list);
        Node target = findNode(root, targetTaskId);

        if(target != null && target.parent != null) {
            int targetIndex = target.parent.children.indexOf(target);
            target.parent.children.remove(targetIndex);
            for(Node node : target.children) {
                node.parent = target.parent;
                target.parent.children.add(targetIndex++, node);
            }
        }

        traverseTreeAndWriteValues(list, root, new AtomicLong(0), -1);
    }
}
