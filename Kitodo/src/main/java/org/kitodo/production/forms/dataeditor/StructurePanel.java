/*
 * (c) Kitodo. Key to digital objects e. V. <contact@kitodo.org>
 *
 * This file is part of the Kitodo project.
 *
 * It is licensed under GNU General Public License version 3 or later.
 *
 * For the full copyright and license information, please read the
 * GPL3-License.txt file that was distributed with this source code.
 */

package org.kitodo.production.forms.dataeditor;

import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.faces.model.SelectItem;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kitodo.api.dataeditor.rulesetmanagement.StructuralElementViewInterface;
import org.kitodo.api.dataformat.LogicalDivision;
import org.kitodo.api.dataformat.MediaVariant;
import org.kitodo.api.dataformat.PhysicalDivision;
import org.kitodo.api.dataformat.View;
import org.kitodo.data.database.beans.Process;
import org.kitodo.data.database.beans.Template;
import org.kitodo.data.exceptions.DataException;
import org.kitodo.exceptions.NoSuchMetadataFieldException;
import org.kitodo.exceptions.UnknownTreeNodeDataException;
import org.kitodo.production.helper.Helper;
import org.kitodo.production.metadata.MetadataEditor;
import org.kitodo.production.model.Subfolder;
import org.kitodo.production.services.ServiceManager;
import org.kitodo.production.services.dataeditor.DataEditorService;
import org.primefaces.event.NodeCollapseEvent;
import org.primefaces.event.NodeExpandEvent;
import org.primefaces.event.NodeSelectEvent;
import org.primefaces.event.TreeDragDropEvent;
import org.primefaces.model.DefaultTreeNode;
import org.primefaces.model.TreeNode;

public class StructurePanel implements Serializable {
    private static final Logger logger = LogManager.getLogger(StructurePanel.class);

    private final DataEditorForm dataEditor;

    /**
     * If changing the tree node fails, we need this value to undo the user’s
     * select action.
     */
    private TreeNode previouslySelectedLogicalNode;

    /**
     * If changing the tree node fails, we need this value to undo the user’s
     * select action.
     */
    private TreeNode previouslySelectedPhysicalNode;

    private TreeNode selectedLogicalNode;

    private TreeNode selectedPhysicalNode;

    private LogicalDivision structure;

    /**
     * The logical structure tree of the edited document.
     */
    private DefaultTreeNode logicalTree = null;

    /**
     * The physical structure tree of the edited document.
     */
    private DefaultTreeNode physicalTree = null;

    /**
     * HashMap containing the current expansion states of all TreeNodes in the logical structure tree.
     */
    private HashMap<LogicalDivision, Boolean> previousExpansionStatesLogicalTree;

    /**
     * HashMap containing the current expansion states of all TreeNodes in the physical structure tree.
     */
    private HashMap<PhysicalDivision, Boolean> previousExpansionStatesPhysicalTree;

    /**
     * HashMap acting as cache for faster retrieval of Subfolders.
     */
    Map<String, Subfolder> subfoldersCache = new HashMap<>();

    /**
     * List of all physicalDivisions assigned to multiple LogicalDivisions.
     */
    private List<PhysicalDivision> severalAssignments = new LinkedList<>();

    /**
     * Variable used to set the correct order value when building the logical and physical trees from the PrimeFaces tree.
     */
    private int order = 1;

    /**
     * Active tabs in StructurePanel's accordion.
     */
    private String activeTabs;

    private String titleMetadata = "type";

    /**
     * Creates a new structure panel.
     *
     * @param dataEditor
     *            the master metadata editor
     */
    StructurePanel(DataEditorForm dataEditor) {
        this.dataEditor = dataEditor;
    }

    /**
     * Clear content.
     */
    public void clear() {
        logicalTree = null;
        physicalTree = null;
        selectedLogicalNode = null;
        selectedPhysicalNode = null;
        previouslySelectedLogicalNode = null;
        previouslySelectedPhysicalNode = null;
        structure = null;
        subfoldersCache = new HashMap<>();
        severalAssignments = new LinkedList<>();
    }

    void deleteSelectedStructure() {
        if (getSelectedStructure().isEmpty()) {
            /*
             * No element is selected or the selected element is not a structure
             * but, for example, a physical division.
             */
            return;
        }
        LogicalDivision selectedStructure = getSelectedStructure().get();
        LinkedList<LogicalDivision> ancestors = MetadataEditor.getAncestorsOfLogicalDivision(selectedStructure,
            structure);
        if (ancestors.isEmpty()) {
            // The selected element is the root node of the tree.
            return;
        }

        Collection<View> subViews = new ArrayList<>();
        getAllSubViews(selectedStructure, subViews);

        List<View> multipleViews = subViews.stream().filter(v -> v.getPhysicalDivision().getLogicalDivisions().size() > 1)
                .collect(Collectors.toList());
        for (View view : multipleViews) {
            dataEditor.unassignView(selectedStructure, view, selectedStructure.getViews().getLast().equals(view));
            if (view.getPhysicalDivision().getLogicalDivisions().size() <= 1) {
                severalAssignments.remove(view.getPhysicalDivision());
            }
        }
        subViews.removeAll(multipleViews);

        LogicalDivision parent = ancestors.getLast();

        parent.getViews().addAll(subViews);
        parent.getViews().sort(Comparator.comparingInt(v -> v.getPhysicalDivision().getOrder()));

        parent.getChildren().remove(selectedStructure);
        show();
        dataEditor.getGalleryPanel().updateStripes();
    }

    private void getAllSubViews(LogicalDivision selectedStructure, Collection<View> views) {
        if (Objects.nonNull(selectedStructure.getViews())) {
            views.addAll(selectedStructure.getViews());
        }
        for (LogicalDivision child : selectedStructure.getChildren()) {
            getAllSubViews(child, views);
        }
    }

    void deleteSelectedPhysicalDivision() {
        for (Pair<PhysicalDivision, LogicalDivision> selectedPhysicalDivision : dataEditor.getSelectedMedia()) {
            if (!dataEditor.getUnsavedDeletedMedia().contains(selectedPhysicalDivision.getKey())) {
                if (selectedPhysicalDivision.getKey().getLogicalDivisions().size() > 1) {
                    Helper.setMessage(selectedPhysicalDivision.getKey().toString() + ": is removed fom all assigned structural elements");
                }
                for (LogicalDivision structuralElement : selectedPhysicalDivision.getKey().getLogicalDivisions()) {
                    structuralElement.getViews().removeIf(view -> view.getPhysicalDivision().equals(selectedPhysicalDivision.getKey()));
                }
                selectedPhysicalDivision.getKey().getLogicalDivisions().clear();
                LinkedList<PhysicalDivision> ancestors = MetadataEditor.getAncestorsOfPhysicalDivision(selectedPhysicalDivision.getKey(),
                        dataEditor.getWorkpiece().getPhysicalStructure());
                if (ancestors.isEmpty()) {
                    // The selected element is the root node of the tree.
                    return;
                }
                PhysicalDivision parent = ancestors.getLast();
                parent.getChildren().remove(selectedPhysicalDivision.getKey());
                dataEditor.getUnsavedDeletedMedia().add(selectedPhysicalDivision.getKey());
            }
        }
        int i = 1;
        for (PhysicalDivision physicalDivision : dataEditor.getWorkpiece().getAllPhysicalDivisionChildrenFilteredByTypePageAndSorted()) {
            physicalDivision.setOrder(i);
            i++;
        }
        show();
        dataEditor.getMetadataPanel().clear();
        dataEditor.getSelectedMedia().clear();
        dataEditor.getGalleryPanel().updateMedia();
        dataEditor.getGalleryPanel().updateStripes();
        dataEditor.getPaginationPanel().show();
    }

    /**
     * Get selected logical TreeNode.
     *
     * @return value of selectedLogicalNode
     */
    public TreeNode getSelectedLogicalNode() {
        return selectedLogicalNode;
    }

    /**
     * Set selected logical TreeNode.
     *
     * @param selected
     *          TreeNode that will be selected
     */
    public void setSelectedLogicalNode(TreeNode selected) {
        if (Objects.nonNull(selected)) {
            this.selectedLogicalNode = selected;
            expandNode(selected.getParent());
        }
    }

    /**
     * Get selectedPhysicalNode.
     *
     * @return value of selectedPhysicalNode
     */
    public TreeNode getSelectedPhysicalNode() {
        return selectedPhysicalNode;
    }

    /**
     * Set selectedPhysicalNode.
     *
     * @param selectedPhysicalNode as org.primefaces.model.TreeNode
     */
    public void setSelectedPhysicalNode(TreeNode selectedPhysicalNode) {
        if (Objects.nonNull(selectedPhysicalNode)) {
            this.selectedPhysicalNode = selectedPhysicalNode;
            expandNode(selectedPhysicalNode.getParent());
        }
    }

    Optional<LogicalDivision> getSelectedStructure() {
        StructureTreeNode structureTreeNode = (StructureTreeNode) selectedLogicalNode.getData();
        Object dataObject = structureTreeNode.getDataObject();
        return Optional.ofNullable(dataObject instanceof LogicalDivision ? (LogicalDivision) dataObject : null);
    }

    Optional<PhysicalDivision> getSelectedPhysicalDivision() {
        StructureTreeNode structureTreeNode = (StructureTreeNode) selectedPhysicalNode.getData();
        Object dataObject = structureTreeNode.getDataObject();
        return Optional.ofNullable(dataObject instanceof PhysicalDivision ? (PhysicalDivision) dataObject : null);
    }

    /**
     * Select given PhysicalDivision in physical structure tree.
     *
     * @param physicalDivision
     *          PhysicalDivision to be selected in physical structure tree
     */
    void selectPhysicalDivision(PhysicalDivision physicalDivision) {
        TreeNode matchingTreeNode = getMatchingTreeNode(getPhysicalTree(), physicalDivision);
        if (Objects.nonNull(matchingTreeNode)) {
            updatePhysicalNodeSelection(matchingTreeNode);
            matchingTreeNode.setSelected(true);
            previouslySelectedPhysicalNode.setSelected(false);
            show(true);
        }
    }

    private TreeNode getMatchingTreeNode(TreeNode parent, PhysicalDivision physicalDivision) {
        TreeNode matchingTreeNode = null;
        for (TreeNode treeNode : parent.getChildren()) {
            if (Objects.nonNull(treeNode) && treeNode.getData() instanceof StructureTreeNode) {
                StructureTreeNode structureTreeNode = (StructureTreeNode) treeNode.getData();
                if (structureTreeNode.getDataObject() instanceof PhysicalDivision) {
                    PhysicalDivision currentPhysicalDivision = (PhysicalDivision) structureTreeNode.getDataObject();
                    if (Objects.nonNull(currentPhysicalDivision.getDivId())
                            && currentPhysicalDivision.getDivId().equals(physicalDivision.getDivId())) {
                        matchingTreeNode = treeNode;
                        break;
                    } else {
                        matchingTreeNode = getMatchingTreeNode(treeNode, physicalDivision);
                        if (Objects.nonNull(matchingTreeNode)) {
                            break;
                        }
                    }
                }
            }
        }
        return matchingTreeNode;
    }

    /**
     * Get logicalTree.
     *
     * @return value of logicalTree
     */
    public DefaultTreeNode getLogicalTree() {
        return this.logicalTree;
    }

    /**
     * Get physicalTree.
     *
     * @return value of physicalTree
     */
    public DefaultTreeNode getPhysicalTree() {
        return physicalTree;
    }

    void preserve() throws UnknownTreeNodeDataException {
        if (isSeparateMedia()) {
            this.preserveLogical();
            this.preservePhysical();
        } else {
            preserveLogicalAndPhysical();
        }
    }

    /**
     * Updates the live structure of the workpiece with the current members of
     * the structure tree in their given order. The live structure of the
     * workpiece which is stored in the logical structure of the structure tree.
     */
    private void preserveLogical() {
        if (!this.logicalTree.getChildren().isEmpty()) {
            preserveLogicalRecursive(this.logicalTree.getChildren().get(logicalTree.getChildCount() - 1));
            this.dataEditor.checkForChanges();
        }
    }

    /**
     * Updates the live structure of a structure tree node and returns it, to
     * provide for updating the parent. If the tree node contains children which
     * aren’t structures, {@code null} is returned to skip them on the level
     * above.
     */
    private static LogicalDivision preserveLogicalRecursive(TreeNode treeNode) {
        StructureTreeNode structureTreeNode = (StructureTreeNode) treeNode.getData();
        if (Objects.isNull(structureTreeNode) || !(structureTreeNode.getDataObject() instanceof LogicalDivision)) {
            return null;
        }
        LogicalDivision structure = (LogicalDivision) structureTreeNode.getDataObject();

        List<LogicalDivision> childrenLive = structure.getChildren();
        childrenLive.clear();
        for (TreeNode child : treeNode.getChildren()) {
            LogicalDivision maybeChildStructure = preserveLogicalRecursive(child);
            if (Objects.nonNull(maybeChildStructure)) {
                childrenLive.add(maybeChildStructure);
            }
        }
        return structure;
    }

    private void preservePhysical() {
        if (!physicalTree.getChildren().isEmpty()) {
            preservePhysicalRecursive(physicalTree.getChildren().get(0));
            this.dataEditor.checkForChanges();
        }
    }

    private static PhysicalDivision preservePhysicalRecursive(TreeNode treeNode) {
        StructureTreeNode structureTreeNode = (StructureTreeNode) treeNode.getData();
        if (Objects.isNull(structureTreeNode) || !(structureTreeNode.getDataObject() instanceof PhysicalDivision)) {
            return null;
        }
        PhysicalDivision physicalDivision = (PhysicalDivision) structureTreeNode.getDataObject();

        List<PhysicalDivision> childrenLive = physicalDivision.getChildren();
        childrenLive.clear();
        for (TreeNode child : treeNode.getChildren()) {
            PhysicalDivision possibleChildPhysicalDivision = preservePhysicalRecursive(child);
            if (Objects.nonNull(possibleChildPhysicalDivision)) {
                childrenLive.add(possibleChildPhysicalDivision);
            }
        }
        return physicalDivision;
    }

    /**
     * Loads the tree(s) into the panel and sets the selected element to the
     * logical structure of the structure tree.
     *
     * @param keepSelection
     *            if true, keeps the currently selected node(s)
     */
    public void show(boolean keepSelection) {
        if (keepSelection) {
            String logicalRowKey = null;
            if (Objects.nonNull(selectedLogicalNode)) {
                logicalRowKey = selectedLogicalNode.getRowKey();
            }
            String physicalRowKey = null;
            if (Objects.nonNull(selectedPhysicalNode)) {
                physicalRowKey = selectedPhysicalNode.getRowKey();
            }
            TreeNode keepSelectedLogicalNode = selectedLogicalNode;
            TreeNode keepSelectedPhysicalNode = selectedPhysicalNode;
            show();
            selectedLogicalNode = keepSelectedLogicalNode;
            selectedPhysicalNode = keepSelectedPhysicalNode;
            if (Objects.nonNull(logicalRowKey)) {
                restoreSelection(logicalRowKey, this.logicalTree);
            }
            if (Objects.nonNull(physicalRowKey)) {
                restoreSelection(physicalRowKey, this.physicalTree);
            }
        } else {
            show();
        }
    }

    /**
     * Loads the tree(s) into the panel and sets the selected element to the
     * logical structure of the structure tree.
     */
    public void show() {
        this.structure = dataEditor.getWorkpiece().getLogicalStructure();

        this.previousExpansionStatesLogicalTree = getLogicalTreeNodeExpansionStates(this.logicalTree);
        this.logicalTree = buildStructureTree();
        updateLogicalNodeExpansionStates(this.logicalTree, this.previousExpansionStatesLogicalTree);

        this.previousExpansionStatesPhysicalTree = getPhysicalTreeNodeExpansionStates(this.physicalTree);
        this.physicalTree = buildMediaTree(dataEditor.getWorkpiece().getPhysicalStructure());
        updatePhysicalNodeExpansionStates(this.physicalTree, this.previousExpansionStatesPhysicalTree);

        this.selectedLogicalNode = logicalTree.getChildren().get(logicalTree.getChildCount() - 1);
        this.selectedPhysicalNode = physicalTree.getChildren().get(0);
        this.previouslySelectedLogicalNode = selectedLogicalNode;
        this.previouslySelectedPhysicalNode = selectedPhysicalNode;
        this.dataEditor.checkForChanges();
    }

    private void restoreSelection(String rowKey, TreeNode parentNode) {
        for (TreeNode childNode : parentNode.getChildren()) {
            if (Objects.nonNull(childNode) && rowKey.equals(childNode.getRowKey())) {
                childNode.setSelected(true);
                break;
            } else {
                childNode.setSelected(false);
                restoreSelection(rowKey, childNode);
            }
        }
    }

    /**
     * Creates the structure tree. If hierarchical links exist upwards, they are
     * displayed above the tree as separate trees.
     *
     * @return the structure tree(s) and the collection of views displayed in
     *         the tree
     */
    private DefaultTreeNode buildStructureTree() {
        DefaultTreeNode invisibleRootNode = new DefaultTreeNode();
        invisibleRootNode.setExpanded(true);
        addParentLinksRecursive(dataEditor.getProcess(), invisibleRootNode);
        buildStructureTreeRecursively(structure, invisibleRootNode);
        return invisibleRootNode;
    }

    private Collection<View> buildStructureTreeRecursively(LogicalDivision structure, TreeNode result) {
        StructureTreeNode node;
        if (Objects.isNull(structure.getLink())) {
            StructuralElementViewInterface divisionView = dataEditor.getRulesetManagement().getStructuralElementView(
                structure.getType(), dataEditor.getAcquisitionStage(), dataEditor.getPriorityList());
            node = new StructureTreeNode(divisionView.getLabel(),
                    divisionView.isUndefined() && Objects.nonNull(structure.getType()), false, structure);
        } else {
            node = new StructureTreeNode(structure.getLink().getUri().toString(), true, true, structure);
            for (Process child : dataEditor.getCurrentChildren()) {
                try {
                    String type = ServiceManager.getProcessService().getBaseType(child.getId());
                    if (child.getId() == ServiceManager.getProcessService()
                            .processIdFromUri(structure.getLink().getUri())) {
                        StructuralElementViewInterface view = dataEditor.getRulesetManagement().getStructuralElementView(
                            type, dataEditor.getAcquisitionStage(), dataEditor.getPriorityList());
                        node = new StructureTreeNode("[" + child.getId() + "] " + view.getLabel() + " - "
                                + child.getTitle(), view.isUndefined(), true, structure);
                    }
                } catch (DataException e) {
                    Helper.setErrorMessage("metadataReadError", e.getMessage(), logger, e);
                    node = new StructureTreeNode(child.getTitle(), true, true, child);
                }
            }
        }
        /*
         * Creating the tree node by handing over the parent node automatically
         * appends it to the parent as a child. That’s the logic of the JSF
         * framework. So you do not have to add the result anywhere.
         */
        DefaultTreeNode parent = new DefaultTreeNode(node, result);
        if (logicalNodeStateUnknown(this.previousExpansionStatesLogicalTree, parent)) {
            parent.setExpanded(true);
        }

        Set<View> viewsShowingOnAChild = new HashSet<>();
        if (this.isSeparateMedia()) {
            for (LogicalDivision child : structure.getChildren()) {
                viewsShowingOnAChild.addAll(buildStructureTreeRecursively(child, parent));
            }
        } else {
            orderChildrenAndViews(new ArrayList<>(structure.getChildren()), new ArrayList<>(structure.getViews()), parent,
                    viewsShowingOnAChild);
        }
        return viewsShowingOnAChild;
    }

    /**
     * This method appends LogicalDivision children and assigned views while considering the ORDER attribute to create the
     * combined tree with the correct order of logical and physical elements.
     * @param children List of LogicalDivisions which are children of the element represented by the DefaultTreeNode parent
     * @param views List of Views assigned to the element represented by the DefaultTreeNode parent
     * @param parent DefaultTreeNode representing the logical element where all new elements should be appended
     * @param viewsShowingOnAChild Collection of Views displayed in the combined tree
     */
    private void orderChildrenAndViews(List<LogicalDivision> children, List<View> views, DefaultTreeNode parent,
                                       Set<View> viewsShowingOnAChild) {
        List<LogicalDivision> temporaryChildren = new ArrayList<>(children);
        List<View> temporaryViews = new ArrayList<>(views);
        temporaryChildren.removeAll(Collections.singletonList(null));
        temporaryViews.removeAll(Collections.singletonList(null));
        while (temporaryChildren.size() > 0 || temporaryViews.size() > 0) {
            LogicalDivision temporaryChild = null;
            View temporaryView = null;

            if (temporaryChildren.size() > 0) {
                temporaryChild = temporaryChildren.get(0);
            }
            if (temporaryViews.size() > 0) {
                temporaryView = temporaryViews.get(0);
            }

            if (Objects.isNull(temporaryChild) && Objects.isNull(temporaryView)) {
                break;
            }

            if (Objects.nonNull(temporaryChild) && Objects.isNull(temporaryView)
                    || Objects.nonNull(temporaryChild) && temporaryChild.getOrder() <= temporaryView.getPhysicalDivision().getOrder()) {
                viewsShowingOnAChild.addAll(buildStructureTreeRecursively(temporaryChild, parent));
                temporaryChildren.remove(0);
            } else {
                if (!viewsShowingOnAChild.contains(temporaryView)) {
                    addTreeNode(buildViewLabel(temporaryView), false, false, temporaryView, parent);
                    viewsShowingOnAChild.add(temporaryView);
                }
                temporaryViews.remove(0);
            }
        }
    }

    /**
     * Builds the display text for a MediaUnit in the StructurePanel.
     * Using a regular expression to strip leading zeros. (?!$) lookahead ensures
     * that not the entire string will be matched. Using Hashmap for subfolder caching
     *
     * @param view
     *            View which holds the MediaUnit
     * @return the display label of the MediaUnit
     */
    private String buildViewLabel(View view) {
        PhysicalDivision mediaUnit = view.getPhysicalDivision();
        Iterator<Entry<MediaVariant, URI>> mediaFileIterator = mediaUnit.getMediaFiles().entrySet().iterator();
        String canonical = "-";
        if (mediaFileIterator.hasNext()) {
            Entry<MediaVariant, URI> mediaFileEntry = mediaFileIterator.next();
            Subfolder subfolder = this.subfoldersCache.computeIfAbsent(mediaFileEntry.getKey().getUse(),
                use -> new Subfolder(dataEditor.getProcess(), dataEditor.getProcess().getProject().getFolders()
                    .parallelStream().filter(folder -> folder.getFileGroup().equals(use)).findAny()
                    .orElseThrow(() ->  new IllegalStateException("Missing folder with file group \"" + use
                        + "\" in project \"" + dataEditor.getProcess().getProject().getTitle()))));
            canonical = subfolder.getCanonical(mediaFileEntry.getValue());
        }
        return canonical.replaceFirst("^0+(?!$)", "") + " : "
            + (Objects.isNull(mediaUnit.getOrderlabel()) ? "uncounted" : mediaUnit.getOrderlabel());
    }

    /**
     * Adds a tree node to the given parent node. The tree node is set to
     * ‘expanded’.
     *
     * @param parentProcess
     *            parent process of current process
     * @param type
     *            the internal name of the type of node, to be resolved through
     *            the rule set
     * @param parent
     *            parent node to which the new node is to be added
     * @return the generated node so that you can add children to it
     */
    private DefaultTreeNode addTreeNode(Process parentProcess, String type, DefaultTreeNode parent) {
        StructuralElementViewInterface structuralElementView = dataEditor.getRulesetManagement().getStructuralElementView(type,
            dataEditor.getAcquisitionStage(), dataEditor.getPriorityList());
        return addTreeNode("[" + parentProcess.getId() + "] " + structuralElementView.getLabel() + " - "
                        + parentProcess.getTitle(), structuralElementView.isUndefined(), true, null, parent);
    }

    /**
     * Adds a tree node to the given parent node. The tree node is set to
     * ‘expanded’.
     *
     * @param label
     *            Label of the tree node displayed to the user
     * @param undefined
     *            whether the given type in the rule set is undefined. If so,
     *            the node is highlighted in color and marked with a warning
     *            symbol.
     * @param linked
     *            whether the node is a link. If so, it will be marked with a
     *            link symbol.
     * @param dataObject
     *            the internal object represented by the node
     * @param parent
     *            parent node to which the new node is to be added
     * @return the generated node so that you can add children to it
     */
    private DefaultTreeNode addTreeNode(String label, boolean undefined, boolean linked, Object dataObject,
            DefaultTreeNode parent) {
        DefaultTreeNode node = new DefaultTreeNode(new StructureTreeNode(label, undefined, linked, dataObject),
                parent);
        if (dataObject instanceof PhysicalDivision && physicalNodeStateUnknown(this.previousExpansionStatesPhysicalTree, node)
                || dataObject instanceof LogicalDivision
                && logicalNodeStateUnknown(this.previousExpansionStatesLogicalTree, node)) {
            node.setExpanded(true);
        }
        return node;
    }

    /**
     * Recursively adds the parent processes in the display. For each parent
     * process, the recursion is run through once, that is for a newspaper issue
     * twice (annual process, overall process). If this fails (child is not
     * found in the parent process, or I/O error), instead only a link is added
     * to the process and the warning sign is activated.
     *
     * @param child
     *            child process, calling recursion
     * @param tree
     *            list of structure trees, in this list the parent links are
     *            inserted on top, therefore LinkedList
     */
    private void addParentLinksRecursive(Process child, DefaultTreeNode tree) {
        Process parent = child.getParent();
        // Termination condition of recursion, if the process has no parent
        if (Objects.isNull(parent)) {
            return;
        }
        // Process parent link of the parent recursively
        addParentLinksRecursive(parent, tree);
        URI uri = ServiceManager.getProcessService().getMetadataFileUri(parent);
        try {
            LogicalDivision logicalStructure = ServiceManager.getMetsService().loadWorkpiece(uri).getLogicalStructure();
            List<LogicalDivision> logicalDivisionList
                    = MetadataEditor.determineLogicalDivisionPathToChild(logicalStructure, child.getId());
            DefaultTreeNode parentNode = tree;
            if (logicalDivisionList.isEmpty()) {
                /*
                 * Error case: The child is not linked in the parent process.
                 * Show the process title of the parent process and a warning
                 * sign.
                 */
                addTreeNode(parent.getTitle(), true, true, parent, tree);
            } else {
                /*
                 * Default case: Show the path through the parent process to the
                 * linked child
                 */
                for (LogicalDivision logicalDivision : logicalDivisionList) {
                    if (Objects.isNull(logicalDivision.getType())) {
                        break;
                    } else {
                        parentNode = addTreeNode(parent, logicalDivision.getType(), parentNode);
                        parentNode.setExpanded(true);
                    }
                }
            }
        } catch (IOException e) {
            /*
             * Error case: The metadata file of the parent process cannot be
             * loaded. Show the process title of the parent process and the
             * warning sign.
             */
            Helper.setErrorMessage("metadataReadError", e.getMessage(), logger, e);
            addTreeNode(parent.getTitle(), true, true, parent, tree);
        }
    }

    /**
     * Creates the media tree.
     *
     * @param mediaRoot
     *            root of physical divisions to show on the tree
     * @return the media tree
     */
    private DefaultTreeNode buildMediaTree(PhysicalDivision mediaRoot) {
        DefaultTreeNode rootTreeNode = new DefaultTreeNode();
        if (physicalNodeStateUnknown(this.previousExpansionStatesPhysicalTree, rootTreeNode)) {
            rootTreeNode.setExpanded(true);
        }
        buildMediaTreeRecursively(mediaRoot, rootTreeNode);
        return rootTreeNode;
    }

    private void buildMediaTreeRecursively(PhysicalDivision physicalDivision, DefaultTreeNode parentTreeNode) {
        StructuralElementViewInterface divisionView = dataEditor.getRulesetManagement().getStructuralElementView(
                physicalDivision.getType(), dataEditor.getAcquisitionStage(), dataEditor.getPriorityList());
        DefaultTreeNode treeNode = addTreeNode(Objects.equals(physicalDivision.getType(), PhysicalDivision.TYPE_PAGE)
                        ? divisionView.getLabel().concat(" " + physicalDivision.getOrderlabel()) : divisionView.getLabel(),
                false, false, physicalDivision, parentTreeNode);
        if (physicalNodeStateUnknown(this.previousExpansionStatesPhysicalTree, treeNode)) {
            treeNode.setExpanded(true);
        }
        if (Objects.nonNull(physicalDivision.getChildren())) {
            for (PhysicalDivision child : physicalDivision.getChildren()) {
                buildMediaTreeRecursively(child, treeNode);
            }
        }
    }

    /**
     * Callback function triggered when a node is selected in the logical structure tree.
     *
     * @param event
     *            NodeSelectEvent triggered by logical node being selected
     */
    public void treeLogicalSelect(NodeSelectEvent event) {
        /*
         * The newly selected element has already been set in 'selectedLogicalNode' by
         * JSF at this point.
         */
        try {
            dataEditor.switchStructure(event.getTreeNode().getData(), true);
            previouslySelectedLogicalNode = selectedLogicalNode;
        } catch (NoSuchMetadataFieldException e) {
            Helper.setErrorMessage(e.getLocalizedMessage(), logger, e);
            selectedLogicalNode = previouslySelectedLogicalNode;
        }
    }

    /**
     * Callback function triggered when a node is selected in the physical structure tree.
     *
     * @param event
     *            NodeSelectEvent triggered by logical node being selected
     */
    public void treePhysicalSelect(NodeSelectEvent event) {
        /*
         * The newly selected element has already been set in 'selectedPhysicalNode' by
         * JSF at this point.
         */
        try {
            dataEditor.switchPhysicalDivision();
            previouslySelectedPhysicalNode = selectedPhysicalNode;
        } catch (NoSuchMetadataFieldException e) {
            Helper.setErrorMessage(e.getLocalizedMessage(), logger, e);
            selectedPhysicalNode = previouslySelectedPhysicalNode;
        }
    }

    void updateNodeSelection(GalleryMediaContent galleryMediaContent, LogicalDivision structure) {
        this.updateLogicalNodeSelection(galleryMediaContent, structure);
        this.updatePhysicalNodeSelection(galleryMediaContent);
    }

    private void updatePhysicalNodeSelection(TreeNode treeNode) {
        if (this.isSeparateMedia()) {
            if (Objects.nonNull(previouslySelectedPhysicalNode)) {
                previouslySelectedPhysicalNode.setSelected(false);
            }
            if (Objects.nonNull(selectedPhysicalNode) && !selectedPhysicalNode.equals(treeNode)) {
                selectedPhysicalNode.setSelected(false);
            }
            if (Objects.nonNull(physicalTree) && Objects.nonNull(treeNode)) {
                setSelectedPhysicalNode(treeNode);
                this.dataEditor.getMetadataPanel().showPhysical(this.dataEditor.getSelectedPhysicalDivision());
            }
        }
    }

    void updatePhysicalNodeSelection(GalleryMediaContent galleryMediaContent) {
        if (Objects.nonNull(physicalTree)) {
            TreeNode selectedTreeNode = updatePhysicalNodeSelectionRecursive(galleryMediaContent, physicalTree);
            updatePhysicalNodeSelection(selectedTreeNode);
        }
    }

    void updateLogicalNodeSelection(GalleryMediaContent galleryMediaContent, LogicalDivision structure) {
        if (Objects.nonNull(previouslySelectedLogicalNode)) {
            previouslySelectedLogicalNode.setSelected(false);
        }
        if (Objects.nonNull(selectedLogicalNode)) {
            selectedLogicalNode.setSelected(false);
        }
        if (Objects.nonNull(this.logicalTree)) {
            if (Objects.isNull(structure)) {
                GalleryStripe matchingGalleryStripe = this.dataEditor.getGalleryPanel().getLogicalStructureOfMedia(galleryMediaContent);
                if (Objects.nonNull(matchingGalleryStripe)) {
                    structure = matchingGalleryStripe.getStructure();
                }
            }
            if (Objects.nonNull(structure)) {
                TreeNode selectedTreeNode;
                if (this.isSeparateMedia()) {
                    selectedTreeNode = updateLogicalNodeSelectionRecursive(structure, logicalTree);
                } else {
                    selectedTreeNode = updatePhysSelectionInLogTreeRecursive(galleryMediaContent.getView().getPhysicalDivision(), structure,
                            logicalTree);
                }
                if (Objects.nonNull(selectedTreeNode)) {
                    setSelectedLogicalNode(selectedTreeNode);
                } else {
                    Helper.setErrorMessage("Unable to update node selection in logical structure!");
                }
            }
        }
    }

    void updateLogicalNodeSelection(LogicalDivision logicalDivision) {
        if (Objects.nonNull(previouslySelectedLogicalNode)) {
            previouslySelectedLogicalNode.setSelected(false);
        }
        if (Objects.nonNull(selectedLogicalNode)) {
            selectedLogicalNode.setSelected(false);
        }
        if (Objects.nonNull(logicalTree)) {
            TreeNode selectedTreeNode = updateLogicalNodeSelectionRecursive(logicalDivision, logicalTree);
            if (Objects.nonNull(selectedTreeNode)) {
                setSelectedLogicalNode(selectedTreeNode);
                try {
                    dataEditor.switchStructure(selectedTreeNode.getData(), false);
                } catch (NoSuchMetadataFieldException e) {
                    logger.error(e.getLocalizedMessage());
                }
            } else {
                Helper.setErrorMessage("Unable to update node selection in logical structure!");
            }
        }
    }

    /**
     * Update the node selection in logical tree.
     * @param structure the LogicalDivision to be selected as a TreeNode
     * @param treeNode the logical structure tree
     * @return the TreeNode that will be selected
     */
    public TreeNode updateLogicalNodeSelectionRecursive(LogicalDivision structure, TreeNode treeNode) {
        TreeNode matchingTreeNode = null;
        for (TreeNode currentTreeNode : treeNode.getChildren()) {
            if (treeNodeMatchesStructure(structure, currentTreeNode)) {
                currentTreeNode.setSelected(true);
                matchingTreeNode = currentTreeNode;
            } else {
                matchingTreeNode = updateLogicalNodeSelectionRecursive(structure, currentTreeNode);
            }
            if (Objects.nonNull(matchingTreeNode)) {
                break;
            }
        }
        return matchingTreeNode;
    }

    private TreeNode updatePhysicalNodeSelectionRecursive(GalleryMediaContent galleryMediaContent, TreeNode treeNode) {
        if (Objects.isNull(galleryMediaContent)) {
            return null;
        }
        TreeNode matchingTreeNode = null;
        for (TreeNode currentTreeNode : treeNode.getChildren()) {
            if (currentTreeNode.getChildCount() < 1 && treeNodeMatchesGalleryMediaContent(galleryMediaContent, currentTreeNode)) {
                currentTreeNode.setSelected(true);
                matchingTreeNode = currentTreeNode;
            } else {
                currentTreeNode.setSelected(false);
                matchingTreeNode = updatePhysicalNodeSelectionRecursive(galleryMediaContent, currentTreeNode);
            }
            if (Objects.nonNull(matchingTreeNode)) {
                break;
            }
        }
        return matchingTreeNode;
    }

    private TreeNode updatePhysSelectionInLogTreeRecursive(PhysicalDivision selectedPhysicalDivision, LogicalDivision parentElement,
                                                           TreeNode treeNode) {
        TreeNode matchingTreeNode = null;
        for (TreeNode currentTreeNode : treeNode.getChildren()) {
            if (treeNode.getData() instanceof StructureTreeNode
                    && Objects.nonNull(((StructureTreeNode) treeNode.getData()).getDataObject())
                    && ((StructureTreeNode) treeNode.getData()).getDataObject().equals(parentElement)
                    && currentTreeNode.getData() instanceof StructureTreeNode
                    && ((StructureTreeNode) currentTreeNode.getData()).getDataObject() instanceof View
                    && ((View) ((StructureTreeNode) currentTreeNode.getData()).getDataObject()).getPhysicalDivision()
                            .equals(selectedPhysicalDivision)) {
                currentTreeNode.setSelected(true);
                matchingTreeNode = currentTreeNode;
            } else {
                currentTreeNode.setSelected(false);
                matchingTreeNode = updatePhysSelectionInLogTreeRecursive(selectedPhysicalDivision, parentElement, currentTreeNode);
            }
            if (Objects.nonNull(matchingTreeNode)) {
                break;
            }
        }
        return matchingTreeNode;
    }

    private boolean treeNodeMatchesGalleryMediaContent(GalleryMediaContent galleryMediaContent, TreeNode treeNode) {
        if (treeNode.getData() instanceof StructureTreeNode) {
            StructureTreeNode structureTreeNode = (StructureTreeNode) treeNode.getData();
            PhysicalDivision physicalDivision = null;
            if (structureTreeNode.getDataObject() instanceof PhysicalDivision) {
                physicalDivision = (PhysicalDivision) structureTreeNode.getDataObject();
            } else if (structureTreeNode.getDataObject() instanceof View) {
                View view = (View) structureTreeNode.getDataObject();
                physicalDivision = view.getPhysicalDivision();
            }
            if (Objects.nonNull(physicalDivision) && Objects.nonNull(galleryMediaContent.getView())) {
                return Objects.equals(physicalDivision, galleryMediaContent.getView().getPhysicalDivision());
            }
        }
        return false;
    }

    private boolean treeNodeMatchesStructure(LogicalDivision structure, TreeNode treeNode) {
        if (Objects.nonNull(treeNode) && treeNode.getData() instanceof StructureTreeNode) {
            StructureTreeNode structureTreeNode = (StructureTreeNode) treeNode.getData();
            if (structureTreeNode.getDataObject() instanceof LogicalDivision) {
                return Objects.equals(structureTreeNode.getDataObject(), structure);
            }
        }
        return false;
    }

    /**
     * Callback function triggered on NodeCollapseEvent. Sets the 'expanded' flag of the corresponding tree node to
     * 'false' because this is not done automatically by PrimeFaces on a NodeCollapseEvent.
     *
     * @param event
     *          the NodeCollapseEvent triggered in the corresponding structure tree
     */
    public void onNodeCollapse(NodeCollapseEvent event) {
        if (Objects.nonNull(event) && Objects.nonNull(event.getTreeNode())) {
            event.getTreeNode().setExpanded(false);
        }
    }

    /**
     * Callback function triggered on NodeExpandEvent. Sets the 'expanded' flag of the corresponding tree node to
     * 'true' because this is not done automatically by PrimeFaces on a NodeExpandEvent.
     *
     * @param event
     *          the NodeExpandEvent triggered in the corresponding structure tree
     */
    public void onNodeExpand(NodeExpandEvent event) {
        if (Objects.nonNull(event) && Objects.nonNull(event.getTreeNode())) {
            event.getTreeNode().setExpanded(true);
        }
    }

    /**
     * Callback function triggered on TreeDragDropEvent. Checks whether performed drag'n'drop action is allowed
     * considering ruleset restrictions on structure hierarchy. In case some ruleset rules were violated by the action
     * displays a corresponding error message to the user and reverts tree to prior state.
     *
     * @param event TreeDragDropEvent
     *              event triggering this callback function
     */
    public void onDragDrop(TreeDragDropEvent event) {

        Object dragNodeObject = event.getDragNode().getData();
        Object dropNodeObject = event.getDropNode().getData();

        expandNode(event.getDropNode());

        try {
            StructureTreeNode dropNode = (StructureTreeNode) dropNodeObject;
            StructureTreeNode dragNode = (StructureTreeNode) dragNodeObject;
            if (dropNode.isLinked()) {
                Helper.setErrorMessage("dataEditor.dragNDropLinkError");
                show();
            }
            else if (dragNode.getDataObject() instanceof LogicalDivision
                    && dropNode.getDataObject() instanceof LogicalDivision) {
                checkLogicalDragDrop(dragNode, dropNode);
            } else if (dragNode.getDataObject() instanceof PhysicalDivision
                    && dropNode.getDataObject() instanceof PhysicalDivision) {
                checkPhysicalDragDrop(dragNode, dropNode);
            } else if (dragNode.getDataObject() instanceof View
                     && dropNode.getDataObject() instanceof LogicalDivision) {
                movePageNode(event, dropNode, dragNode);
            } else {
                Helper.setErrorMessage(
                    Helper.getTranslation("dataEditor.dragNDropError", dragNode.getLabel(), dropNode.getLabel()));
                show();
            }
        } catch (Exception exception) {
            logger.error(exception.getLocalizedMessage());
        }
    }

    /**
     * Determine the LogicalDivision to which the given View is assigned.
     *
     * @param view
     *          View for which the LogicalDivision is determined
     * @return the LogicalDivision to which the given View is assigned
     */
    LogicalDivision getPageStructure(View view, LogicalDivision parent) {
        LogicalDivision resultElement = null;
        for (LogicalDivision child : parent.getChildren()) {
            if (child.getViews().contains(view)) {
                resultElement = child;
            } else {
                resultElement =  getPageStructure(view, child);
            }
            if (Objects.nonNull(resultElement)) {
                break;
            }
        }
        return resultElement;
    }

    /**
     * Move page encapsulated in given StructureTreeNode 'dragNode' to Structural Element encapsulated in given
     * StructureTreeNode 'dropNode' at index encoded in given TreeDragDropEvent 'event'.
     *
     * @param event
     *          TreeDragDropEvent triggering 'movePageNode'
     * @param dropNode
     *          StructureTreeNode containing the Structural Element to which the page is moved
     * @param dragNode
     *          StructureTreeNode containing the View/Page that is moved
     */
    private void movePageNode(TreeDragDropEvent event, StructureTreeNode dropNode, StructureTreeNode dragNode) throws Exception {
        TreeNode dragParent = event.getDragNode().getParent();
        if (dragParent.getData() instanceof StructureTreeNode) {
            StructureTreeNode dragParentTreeNode = (StructureTreeNode) dragParent.getData();
            if (dragParentTreeNode.getDataObject() instanceof LogicalDivision) {
                // FIXME waiting for PrimeFaces' tree drop index bug to be fixed.
                // Until fixed dropping nodes onto other nodes will produce random drop indices.
                preserveLogicalAndPhysical();
                show();
                expandNode(event.getDropNode());
                dataEditor.getGalleryPanel().updateStripes();
                dataEditor.getGalleryPanel().updateMedia();
                return;
            } else {
                Helper.setErrorMessage(
                    Helper.getTranslation("dataEditor.dragNDropError", dragNode.getLabel(), dropNode.getLabel()));
            }
        } else {
            Helper.setErrorMessage(
                Helper.getTranslation("dataEditor.dragNDropError", dragNode.getLabel(), dropNode.getLabel()));
        }
        show();
    }

    /**
     * Change the order of the PhysicalDivisions in the workpiece.
     * When structure is saved to METS this is represented by the order of DIV elements in the physical structMap.
     * @param toElement logical element where to which the PhysicalDivisions are assigned
     * @param elementsToBeMoved List of PhysicalDivisions which are moved
     * @param insertionIndex index at which the PhysicalDivisions are added to the existing List of PhysicalDivisions.
     *                       The value -1 represents the end of the list.
     */
    void reorderPhysicalDivisions(LogicalDivision toElement,
                           List<Pair<View, LogicalDivision>> elementsToBeMoved,
                           int insertionIndex) {
        int physicalInsertionIndex;
        List<PhysicalDivision> physicalDivisionsToBeMoved = elementsToBeMoved.stream()
                .map(e -> e.getLeft().getPhysicalDivision())
                .collect(Collectors.toList());

        if (insertionIndex > toElement.getViews().size()) {
            Helper.setErrorMessage("Unsupported drag'n'drop operation: Insertion index exceeds list.");
            insertionIndex = -1;
        }

        if (insertionIndex < 0 || toElement.getViews().size() == 0) {
            // no insertion position was specified or the element does not contain any pages yet
            physicalInsertionIndex = toElement.getOrder() - 1;
        } else {
            // if 'insertionIndex' equals the size of the list, it means we want to append the moved pages _behind_ the physical division of
            // the last view in the list of views of the 'toElement'
            if (insertionIndex == toElement.getViews().size()) {
                physicalInsertionIndex = toElement.getViews().getLast().getPhysicalDivision().getOrder();
            } else if (insertionIndex == 0) {
                // insert at first position directly after logical element
                physicalInsertionIndex = toElement.getOrder() - 1;
            } else {
                // insert at given index
                physicalInsertionIndex = toElement.getViews().get(insertionIndex).getPhysicalDivision().getOrder() - 1;
            }
        }

        if (physicalInsertionIndex > physicalDivisionsToBeMoved.stream()
                .map(PhysicalDivision::getOrder)
                .collect(Collectors.summarizingInt(Integer::intValue))
                .getMin() - 1) {
            int finalInsertionIndex = physicalInsertionIndex;
            physicalInsertionIndex -= (int) physicalDivisionsToBeMoved.stream().filter(m -> m.getOrder() - 1 < finalInsertionIndex).count();
        }
        dataEditor.getWorkpiece().getPhysicalStructure().getChildren().removeAll(physicalDivisionsToBeMoved);
        int numberOfChildren = dataEditor.getWorkpiece().getPhysicalStructure().getChildren().size();
        if (physicalInsertionIndex < numberOfChildren) {
            dataEditor.getWorkpiece().getPhysicalStructure().getChildren().addAll(physicalInsertionIndex, physicalDivisionsToBeMoved);
        } else {
            dataEditor.getWorkpiece().getPhysicalStructure().getChildren().addAll(physicalDivisionsToBeMoved);
            if (physicalInsertionIndex > numberOfChildren) {
                Helper.setErrorMessage("Could not append media at correct position. Index exceeded list.");
            }
        }
    }

    /**
     * Change order fields of physical elements. When saved to METS this is represented by the physical structMap divs' "ORDER" attribute.
     * @param toElement Logical element to which the physical elements are assigned. The physical elements' order follows the order of the
     *                  logical elements.
     * @param elementsToBeMoved List of physical elements to be moved
     */
    void changePhysicalOrderFields(LogicalDivision toElement, List<Pair<View, LogicalDivision>> elementsToBeMoved) {
        ServiceManager.getFileService().renumberPhysicalDivisions(dataEditor.getWorkpiece(), false);
    }

    /**
     * Change the order attribute of the logical elements that are affected by pages around them being moved.
     * @param toElement logical element the pages will be assigned to
     * @param elementsToBeMoved physical elements which are moved
     */
    void changeLogicalOrderFields(LogicalDivision toElement, List<Pair<View, LogicalDivision>> elementsToBeMoved,
                                  int insertionIndex) {
        HashMap<Integer, List<LogicalDivision>> logicalElementsByOrder = new HashMap<>();
        for (LogicalDivision logicalElement : dataEditor.getWorkpiece().getAllLogicalDivisions()) {
            if (logicalElementsByOrder.containsKey(logicalElement.getOrder()))  {
                logicalElementsByOrder.get(logicalElement.getOrder()).add(logicalElement);
            } else {
                logicalElementsByOrder.put(logicalElement.getOrder(), new LinkedList<>(Collections.singletonList(logicalElement)));
            }
        }

        // Order values of moved pages and target element. Logical elements located between these Order values are affected.
        List<Integer> ordersAffectedByMove = getOrdersAffectedByMove(elementsToBeMoved, toElement);

        /* The new Order value for the logical elements can be calculated quite simple:
        The Order values of elements located before the target element have to be modified by -i - 1.
        The Order values of elements located after the target element have to be modified by the size of ordersAffectedByMove - i.
        (ordersAffectedByMove equals to the number of moved pages + the target element.)
         */

        for (Map.Entry<Integer, List<LogicalDivision>> entry : logicalElementsByOrder.entrySet()) {
            for (int i = 0; i < ordersAffectedByMove.size() - 1; i++) {
                if (ordersAffectedByMove.get(i) < entry.getKey() && entry.getKey() < ordersAffectedByMove.get(i + 1)) {
                    if (ordersAffectedByMove.get(i) < toElement.getOrder()) {
                        updateOrder(entry.getValue(), -i - 1);
                    } else if (ordersAffectedByMove.get(i) > toElement.getOrder()) {
                        updateOrder(entry.getValue(), ordersAffectedByMove.size() - i);
                    }
                }
            }
            // check if elements exist with the same order like toElement (the toElememt itself might be affected as well)
            if (entry.getKey() == toElement.getOrder()) {
                List<LogicalDivision> beforeToElement = entry.getValue().subList(0, entry.getValue().indexOf(toElement) + 1);
                List<LogicalDivision> afterToElement = entry.getValue().subList(entry.getValue().indexOf(toElement) + 1,
                        entry.getValue().size());
                /* toElement at index 0 means we're in an edge case: toElement is the first order which is affected (no pages with smaller
                order affected) and its order will not change, nor will other elements with the same order before it. */
                if (ordersAffectedByMove.indexOf(toElement.getOrder()) > 0) {
                    updateOrder(beforeToElement, -ordersAffectedByMove.indexOf(entry.getKey()));
                }
                /* Order of elements directly after toElement (with same order) only have to be update if the pages are inserted at the
                first position. If they are inserted after any pages, the order of elements in afterToElement will not change. */
                if (insertionIndex == 0) {
                    updateOrder(afterToElement, elementsToBeMoved.size() - ordersAffectedByMove.indexOf(toElement.getOrder()));
                }
            }
        }
    }

    private List<Integer> getOrdersAffectedByMove(List<Pair<View, LogicalDivision>> views, LogicalDivision toElement) {
        Set<Integer> ordersAffectedByMove = views.stream()
                .map(e -> e.getLeft().getPhysicalDivision().getOrder())
                .collect(Collectors.toSet());
        ordersAffectedByMove.add(toElement.getOrder());
        return ordersAffectedByMove.stream()
                .sorted()
                .collect(Collectors.toList());
    }

    private void updateOrder(List<LogicalDivision> elementsToBeUpdated, int delta) {
        for (LogicalDivision element : elementsToBeUpdated) {
            element.setOrder(element.getOrder() + delta);
        }
    }

    /**
     * Move List of elements 'elementsToBeMoved' from LogicalDivision in each Pair to LogicalDivision
     * 'toElement'.
     *
     * @param toElement
     *          LogicalDivision to which View is moved
     * @param elementsToBeMoved
     *          List of elements to be moved as Pairs of View and LogicalDivision they are attached to
     * @param insertionIndex
     *          Index where views will be inserted into toElement's views
     */
    void moveViews(LogicalDivision toElement,
                   List<Pair<View, LogicalDivision>> elementsToBeMoved,
                   int insertionIndex) {
        List<View> views = elementsToBeMoved.stream()
                .map(Pair::getKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (insertionIndex < 0 || insertionIndex == toElement.getViews().size()) {
            toElement.getViews().addAll(views);
        } else {
            toElement.getViews().addAll(insertionIndex, views);
        }

        for (Pair<View, LogicalDivision> elementToBeMoved : elementsToBeMoved) {
            boolean removeLastOccurrenceOfView = toElement.equals(elementToBeMoved.getValue())
                    && insertionIndex < elementToBeMoved.getValue().getViews().lastIndexOf(elementToBeMoved.getKey());
            dataEditor.unassignView(elementToBeMoved.getValue(), elementToBeMoved.getKey(), removeLastOccurrenceOfView);
            elementToBeMoved.getKey().getPhysicalDivision().getLogicalDivisions().add(toElement);
        }
    }

    private void checkLogicalDragDrop(StructureTreeNode dragNode, StructureTreeNode dropNode) {

        LogicalDivision dragStructure = (LogicalDivision) dragNode.getDataObject();
        LogicalDivision dropStructure = (LogicalDivision) dropNode.getDataObject();

        StructuralElementViewInterface divisionView = dataEditor.getRulesetManagement().getStructuralElementView(
                dropStructure.getType(), dataEditor.getAcquisitionStage(), dataEditor.getPriorityList());

        LinkedList<LogicalDivision> dragParents;
        if (divisionView.getAllowedSubstructuralElements().containsKey(dragStructure.getType())) {
            dragParents = MetadataEditor.getAncestorsOfLogicalDivision(dragStructure,
                    dataEditor.getWorkpiece().getLogicalStructure());
            if (!dragParents.isEmpty()) {
                LogicalDivision parentStructure = dragParents.get(dragParents.size() - 1);
                if (parentStructure.getChildren().contains(dragStructure)) {
                    if (isSeparateMedia()) {
                        preserveLogical();
                    } else {
                        preserveLogicalAndPhysical();
                    }
                    this.dataEditor.getGalleryPanel().updateStripes();
                } else {
                    Helper.setErrorMessage(Helper.getTranslation("dataEditor.childNotContainedError",
                        dragNode.getLabel()));
                }
            } else {
                Helper.setErrorMessage(Helper.getTranslation("dataEditor.noParentsError",
                    dragNode.getLabel()));
            }
        } else {
            Helper.setErrorMessage(Helper.getTranslation("dataEditor.forbiddenChildElement",
                dragNode.getLabel(), dropNode.getLabel()));
        }
        show();
    }

    private void checkPhysicalDragDrop(StructureTreeNode dragNode, StructureTreeNode dropNode) {

        PhysicalDivision dragUnit = (PhysicalDivision) dragNode.getDataObject();
        PhysicalDivision dropUnit = (PhysicalDivision) dropNode.getDataObject();

        StructuralElementViewInterface divisionView = dataEditor.getRulesetManagement().getStructuralElementView(
                dropUnit.getType(), dataEditor.getAcquisitionStage(), dataEditor.getPriorityList());

        LinkedList<PhysicalDivision> dragParents;
        if (divisionView.getAllowedSubstructuralElements().containsKey(dragUnit.getType())) {
            dragParents = MetadataEditor.getAncestorsOfPhysicalDivision(dragUnit, dataEditor.getWorkpiece().getPhysicalStructure());
            if (dragParents.isEmpty()) {
                Helper.setErrorMessage(Helper.getTranslation("dataEditor.noParentsError",
                    dragNode.getLabel()));
            } else {
                PhysicalDivision parentUnit = dragParents.get(dragParents.size() - 1);
                if (parentUnit.getChildren().contains(dragUnit)) {
                    preservePhysical();
                } else {
                    Helper.setErrorMessage(Helper.getTranslation("dataEditor.childNotContainedError",
                        dragUnit.getType()));
                }
            }
        } else {
            Helper.setErrorMessage(Helper.getTranslation("dataEditor.forbiddenChildElement",
                dragNode.getLabel(), dropNode.getLabel()));
        }
        show();
    }

    private void preserveLogicalAndPhysical() throws UnknownTreeNodeDataException {
        if (!this.logicalTree.getChildren().isEmpty()) {
            order = 1;
            for (PhysicalDivision physicalDivision : dataEditor.getWorkpiece().getPhysicalStructure().getChildren()) {
                physicalDivision.getLogicalDivisions().clear();
            }
            dataEditor.getWorkpiece().getPhysicalStructure().getChildren().clear();
            preserveLogicalAndPhysicalRecursive(this.logicalTree.getChildren().get(logicalTree.getChildCount() - 1));
        }
    }

    private LogicalDivision preserveLogicalAndPhysicalRecursive(TreeNode treeNode) throws UnknownTreeNodeDataException {
        StructureTreeNode structureTreeNode = (StructureTreeNode) treeNode.getData();
        if (Objects.isNull(structureTreeNode) || !(structureTreeNode.getDataObject() instanceof LogicalDivision)) {
            return null;
        }
        LogicalDivision structure = (LogicalDivision) structureTreeNode.getDataObject();
        structure.setOrder(order);
        structure.getViews().clear();
        structure.getChildren().clear();
        for (TreeNode child : treeNode.getChildren()) {
            if (!(child.getData() instanceof StructureTreeNode)) {
                throw new UnknownTreeNodeDataException(child.getData().getClass().getCanonicalName());
            }
            if (((StructureTreeNode) child.getData()).getDataObject() instanceof LogicalDivision) {
                LogicalDivision possibleChildStructure = preserveLogicalAndPhysicalRecursive(child);
                if (Objects.nonNull(possibleChildStructure)) {
                    structure.getChildren().add(possibleChildStructure);
                }
            } else if (((StructureTreeNode) child.getData()).getDataObject() instanceof View) {
                View view = (View) ((StructureTreeNode) child.getData()).getDataObject();
                structure.getViews().add(view);
                if (!dataEditor.getWorkpiece().getAllPhysicalDivisions().contains(view.getPhysicalDivision())) {
                    view.getPhysicalDivision().setOrder(order);
                    dataEditor.getWorkpiece().getPhysicalStructure().getChildren().add(view.getPhysicalDivision());
                    order++;
                }
                if (!view.getPhysicalDivision().getLogicalDivisions().contains(structure)) {
                    view.getPhysicalDivision().getLogicalDivisions().add(structure);
                }
            }
        }
        return structure;
    }

    /**
     * Check and return whether the metadata of a process should be displayed in separate logical and physical
     * structure trees or in one unified structure tree.
     *
     * @return
     *          whether metadata structure should be displayed in separate structure trees or not
     */
    public boolean isSeparateMedia() {
        Process process = dataEditor.getProcess();
        if (Objects.nonNull(process)) {
            Template template = process.getTemplate();
            if ( Objects.nonNull(template) ) {
                return template.getWorkflow().isSeparateStructure();
            }
        }
        return false;
    }

    private void expandNode(TreeNode node) {
        if (Objects.nonNull(node)) {
            node.setExpanded(true);
            expandNode(node.getParent());
        }
    }

    private HashMap<LogicalDivision, Boolean> getLogicalTreeNodeExpansionStates(DefaultTreeNode tree) {
        if (Objects.nonNull(tree) && tree.getChildCount() == 1) {
            TreeNode treeRoot = tree.getChildren().get(0);
            LogicalDivision structuralElement = getTreeNodeStructuralElement(treeRoot);
            if (Objects.nonNull(structuralElement)) {
                return getLogicalTreeNodeExpansionStatesRecursively(treeRoot, new HashMap<>());
            }
        }
        return new HashMap<>();
    }

    private HashMap<LogicalDivision, Boolean> getLogicalTreeNodeExpansionStatesRecursively(TreeNode treeNode,
            HashMap<LogicalDivision, Boolean> expansionStates) {
        if (Objects.nonNull(treeNode)) {
            LogicalDivision structureData = getTreeNodeStructuralElement(treeNode);
            if (Objects.nonNull(structureData)) {
                expansionStates.put(structureData, treeNode.isExpanded());
                for (TreeNode childNode : treeNode.getChildren()) {
                    expansionStates.putAll(getLogicalTreeNodeExpansionStatesRecursively(childNode, expansionStates));
                }
            }
        }
        return expansionStates;
    }

    private HashMap<PhysicalDivision, Boolean> getPhysicalTreeNodeExpansionStates(DefaultTreeNode tree) {
        if (Objects.nonNull(tree) && tree.getChildCount() == 1) {
            TreeNode treeRoot = tree.getChildren().get(0);
            PhysicalDivision physicalDivision = getTreeNodePhysicalDivision(treeRoot);
            if (Objects.nonNull(physicalDivision)) {
                return getPhysicalTreeNodeExpansionStatesRecursively(treeRoot, new HashMap<>());
            }
        }
        return new HashMap<>();
    }

    private HashMap<PhysicalDivision, Boolean> getPhysicalTreeNodeExpansionStatesRecursively(TreeNode treeNode,
            HashMap<PhysicalDivision, Boolean> expansionStates) {
        if (Objects.nonNull(treeNode)) {
            PhysicalDivision physicalDivision = getTreeNodePhysicalDivision(treeNode);
            if (Objects.nonNull(physicalDivision)) {
                expansionStates.put(physicalDivision, treeNode.isExpanded());
                for (TreeNode childNode : treeNode.getChildren()) {
                    expansionStates.putAll(getPhysicalTreeNodeExpansionStatesRecursively(childNode, expansionStates));
                }
            }
        }
        return expansionStates;
    }

    private void updateLogicalNodeExpansionStates(DefaultTreeNode tree, HashMap<LogicalDivision, Boolean> expansionStates) {
        if (Objects.nonNull(tree) && Objects.nonNull(expansionStates) && !expansionStates.isEmpty()) {
            updateNodeExpansionStatesRecursively(tree, expansionStates);
        }
    }

    private void updateNodeExpansionStatesRecursively(TreeNode treeNode, HashMap<LogicalDivision, Boolean> expansionStates) {
        LogicalDivision element = getTreeNodeStructuralElement(treeNode);
        if (Objects.nonNull(element) && expansionStates.containsKey(element)) {
            treeNode.setExpanded(expansionStates.get(element));
        }
        for (TreeNode childNode : treeNode.getChildren()) {
            updateNodeExpansionStatesRecursively(childNode, expansionStates);
        }
    }

    private void updatePhysicalNodeExpansionStates(DefaultTreeNode tree, HashMap<PhysicalDivision, Boolean> expansionStates) {
        if (Objects.nonNull(tree) && Objects.nonNull(expansionStates) && !expansionStates.isEmpty()) {
            updatePhysicalNodeExpansionStatesRecursively(tree, expansionStates);
        }
    }

    private void updatePhysicalNodeExpansionStatesRecursively(TreeNode treeNode, HashMap<PhysicalDivision, Boolean> expansionStates) {
        PhysicalDivision physicalDivision = getTreeNodePhysicalDivision(treeNode);
        if (Objects.nonNull(physicalDivision) && expansionStates.containsKey(physicalDivision)) {
            treeNode.setExpanded(expansionStates.get(physicalDivision));
        }
        for (TreeNode childNode : treeNode.getChildren()) {
            updatePhysicalNodeExpansionStatesRecursively(childNode, expansionStates);
        }
    }

    private boolean logicalNodeStateUnknown(HashMap<LogicalDivision, Boolean> expansionStates, TreeNode treeNode) {
        LogicalDivision element = getTreeNodeStructuralElement(treeNode);
        return !Objects.nonNull(expansionStates) || (Objects.nonNull(element) && !expansionStates.containsKey(element));
    }

    private boolean physicalNodeStateUnknown(HashMap<PhysicalDivision, Boolean> expanionStates, TreeNode treeNode) {
        PhysicalDivision physicalDivision = getTreeNodePhysicalDivision(treeNode);
        return Objects.isNull(expanionStates) || (Objects.nonNull(physicalDivision) && !expanionStates.containsKey(physicalDivision));
    }

    private LogicalDivision getTreeNodeStructuralElement(TreeNode treeNode) {
        if (treeNode.getData() instanceof StructureTreeNode) {
            StructureTreeNode structureTreeNode = (StructureTreeNode) treeNode.getData();
            if (structureTreeNode.getDataObject() instanceof LogicalDivision) {
                return (LogicalDivision) structureTreeNode.getDataObject();
            }
        }
        return null;
    }

    private PhysicalDivision getTreeNodePhysicalDivision(TreeNode treeNode) {
        if (treeNode.getData() instanceof StructureTreeNode) {
            StructureTreeNode structureTreeNode = (StructureTreeNode) treeNode.getData();
            if (structureTreeNode.getDataObject() instanceof PhysicalDivision) {
                return (PhysicalDivision) structureTreeNode.getDataObject();
            }
        }
        return null;
    }

    /**
     * Get List of PhysicalDivisions assigned to multiple LogicalDivisions.
     *
     * @return value of severalAssignments
     */
    List<PhysicalDivision> getSeveralAssignments() {
        return severalAssignments;
    }

    /**
     * Get activeTabs.
     *
     * @return value of activeTabs
     */
    public String getActiveTabs() {
        return activeTabs;
    }

    /**
     * Set activeTabs.
     *
     * @param activeTabs as java.lang.String
     */
    public void setActiveTabs(String activeTabs) {
        this.activeTabs = activeTabs;
    }

    /**
     * Get the index of this StructureTreeNode's PhysicalDivision out of all
     * PhysicalDivisions which are assigned to more than one LogicalDivision.
     *
     * @param treeNode
     *            object to find the index for
     * @return index of the StructureTreeNode's PhysicalDivision if present in
     *         the List of several assignments, or -1 if not present in the
     *         list.
     */
    public int getMultipleAssignmentsIndex(StructureTreeNode treeNode) {
        if (treeNode.getDataObject() instanceof View
                && Objects.nonNull(((View) treeNode.getDataObject()).getPhysicalDivision())) {
            return severalAssignments.indexOf(((View) treeNode.getDataObject()).getPhysicalDivision());
        }
        return -1;
    }

    /**
     * Check if the selected Node's PhysicalDivision is assigned to several LogicalDivisions.
     *
     * @return {@code true} when the PhysicalDivision is assigned to more than one logical element
     */
    public boolean isAssignedSeveralTimes() {
        if (Objects.nonNull(selectedLogicalNode) && selectedLogicalNode.getData() instanceof  StructureTreeNode) {
            StructureTreeNode structureTreeNode = (StructureTreeNode) selectedLogicalNode.getData();
            if (structureTreeNode.getDataObject() instanceof View) {
                View view = (View) structureTreeNode.getDataObject();
                return view.getPhysicalDivision().getLogicalDivisions().size() > 1;
            }
        }
        return false;
    }

    /**
     * Check if the selected Node's PhysicalDivision can be assigned to the next logical element in addition to the current assignment.
     * @return {@code true} if the PhysicalDivision can be assigned to the next LogicalDivision
     */
    public boolean isAssignableSeveralTimes() {
        if (Objects.nonNull(selectedLogicalNode) && selectedLogicalNode.getData() instanceof  StructureTreeNode) {
            StructureTreeNode structureTreeNode = (StructureTreeNode) selectedLogicalNode.getData();
            if (structureTreeNode.getDataObject() instanceof View) {
                List<TreeNode> logicalNodeSiblings = selectedLogicalNode.getParent().getParent().getChildren();
                int logicalNodeIndex = logicalNodeSiblings.indexOf(selectedLogicalNode.getParent());
                List<TreeNode> viewSiblings = selectedLogicalNode.getParent().getChildren();
                // check for selected node's positions and siblings after selected node's parent
                if (viewSiblings.indexOf(selectedLogicalNode) == viewSiblings.size() - 1
                        && logicalNodeSiblings.size() > logicalNodeIndex + 1) {
                    TreeNode nextSibling = logicalNodeSiblings.get(logicalNodeIndex + 1);
                    if (nextSibling.getData() instanceof StructureTreeNode) {
                        StructureTreeNode structureTreeNodeSibling = (StructureTreeNode) nextSibling.getData();
                        return structureTreeNodeSibling.getDataObject() instanceof LogicalDivision;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Assign selected Node's PhysicalDivision to the next LogicalDivision.
     */
    public void assign() {
        if (isAssignableSeveralTimes()) {
            View view = (View) ((StructureTreeNode) selectedLogicalNode.getData()).getDataObject();
            View viewToAssign = new View();
            viewToAssign.setPhysicalDivision(view.getPhysicalDivision());
            List<TreeNode> logicalNodeSiblings = selectedLogicalNode.getParent().getParent().getChildren();
            int logicalNodeIndex = logicalNodeSiblings.indexOf(selectedLogicalNode.getParent());
            TreeNode nextSibling = logicalNodeSiblings.get(logicalNodeIndex + 1);
            StructureTreeNode structureTreeNodeSibling = (StructureTreeNode) nextSibling.getData();
            LogicalDivision logicalDivision = (LogicalDivision) structureTreeNodeSibling.getDataObject();
            dataEditor.assignView(logicalDivision, viewToAssign, 0);
            severalAssignments.add(viewToAssign.getPhysicalDivision());
            show();
            dataEditor.getSelectedMedia().clear();
            dataEditor.getGalleryPanel().updateStripes();
        }
    }

    /**
     * Unassign the selected Node's PhysicalDivision from the LogicalDivision parent at the selected position.
     * This does not remove it from other LogicalDivisions.
     */
    public void unassign() {
        if (isAssignedSeveralTimes()) {
            StructureTreeNode structureTreeNode = (StructureTreeNode) selectedLogicalNode.getData();
            View view = (View) structureTreeNode.getDataObject();
            if (selectedLogicalNode.getParent().getData() instanceof StructureTreeNode) {
                StructureTreeNode structureTreeNodeParent = (StructureTreeNode) selectedLogicalNode.getParent().getData();
                if (structureTreeNodeParent.getDataObject() instanceof LogicalDivision) {
                    LogicalDivision logicalDivision =
                            (LogicalDivision) structureTreeNodeParent.getDataObject();
                    dataEditor.unassignView(logicalDivision, view, false);
                    if (view.getPhysicalDivision().getLogicalDivisions().size() <= 1) {
                        severalAssignments.remove(view.getPhysicalDivision());
                    }
                    show();
                    dataEditor.getGalleryPanel().updateStripes();
                }
            }
        }
    }

    /**
     * Get title metadata.
     * @return value of titleMetadata
     */
    public String getTitleMetadata() {
        return titleMetadata;
    }

    /**
     * Set title metadata.
     * @param titleMetadata as java.lang.String
     */
    public void setTitleMetadata(String titleMetadata) {
        this.titleMetadata = titleMetadata;
    }

    /**
     * Get list of metadata keys that are used for displaying title information from the Kitodo configuration file.
     * @return list of title metadata keys
     */
    public List<SelectItem> getTitleMetadataItems() {
        return DataEditorService.getTitleKeys()
                .stream()
                .map(key -> new SelectItem(key,dataEditor.getRulesetManagement().getTranslationForKey(
                        key,dataEditor.getPriorityList()).orElse(key)))
                .collect(Collectors.toList());
    }

}
