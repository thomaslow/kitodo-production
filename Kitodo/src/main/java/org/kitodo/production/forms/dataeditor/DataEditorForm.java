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
import java.io.OutputStream;
import java.io.Serializable;
import java.net.URI;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale.LanguageRange;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.enterprise.context.SessionScoped;
import javax.faces.context.FacesContext;
import javax.faces.model.SelectItem;
import javax.inject.Named;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kitodo.api.dataeditor.rulesetmanagement.RulesetManagementInterface;
import org.kitodo.api.dataformat.LogicalDivision;
import org.kitodo.api.dataformat.PhysicalDivision;
import org.kitodo.api.dataformat.View;
import org.kitodo.api.dataformat.Workpiece;
import org.kitodo.api.validation.State;
import org.kitodo.api.validation.ValidationResult;
import org.kitodo.config.ConfigCore;
import org.kitodo.data.database.beans.DataEditorSetting;
import org.kitodo.data.database.beans.Process;
import org.kitodo.data.database.beans.Project;
import org.kitodo.data.database.beans.User;
import org.kitodo.data.database.exceptions.DAOException;
import org.kitodo.exceptions.InvalidImagesException;
import org.kitodo.exceptions.InvalidMetadataValueException;
import org.kitodo.exceptions.NoSuchMetadataFieldException;
import org.kitodo.production.enums.ObjectType;
import org.kitodo.production.forms.createprocess.ProcessDetail;
import org.kitodo.production.helper.Helper;
import org.kitodo.production.interfaces.MetadataTreeTableInterface;
import org.kitodo.production.interfaces.RulesetSetupInterface;
import org.kitodo.production.metadata.MetadataLock;
import org.kitodo.production.services.ServiceManager;
import org.kitodo.production.services.dataeditor.DataEditorService;
import org.primefaces.PrimeFaces;
import org.primefaces.model.TreeNode;

@Named("DataEditorForm")
@SessionScoped
public class DataEditorForm implements MetadataTreeTableInterface, RulesetSetupInterface, Serializable {

    private static final Logger logger = LogManager.getLogger(DataEditorForm.class);

    /**
     * A filter on the rule set depending on the workflow step. So far this is
     * not configurable anywhere and is therefore on “edit”.
     */
    private final String acquisitionStage;

    /**
     * Backing bean for the add doc struc type dialog.
     */
    private final AddDocStrucTypeDialog addDocStrucTypeDialog;

    /**
     * Dialog for adding metadata.
     */
    private final AddMetadataDialog addMetadataDialog;

    /**
     * Backing bean for the add PhysicalDivision dialog.
     */
    private final AddPhysicalDivisionDialog addPhysicalDivisionDialog;

    /**
     * Backing bean for the change doc struc type dialog.
     */
    private final ChangeDocStrucTypeDialog changeDocStrucTypeDialog;

    /**
     * Backing bean for the edit pages dialog.
     */
    private final EditPagesDialog editPagesDialog;

    private final UploadFileDialog uploadFileDialog ;
    /**
     * Backing bean for the gallery panel.
     */
    private final GalleryPanel galleryPanel;

    /**
     * The current process children.
     */
    private final Set<Process> currentChildren = new HashSet<>();

    /**
     * The path to the main file, to save it later.
     */
    private URI mainFileUri;

    /**
     * Backing bean for the metadata panel.
     */
    private final MetadataPanel metadataPanel;

    /**
     * Backing bean for the pagination panel.
     */
    private final PaginationPanel paginationPanel;

    /**
     * The language preference list of the editing user for displaying the
     * metadata labels. We cache this because it’s used thousands of times and
     * otherwise the access would always go through the search engine, which
     * would delay page creation.
     */
    private List<LanguageRange> priorityList;

    /**
     * Process whose workpiece is under edit.
     */
    private Process process;

    private String referringView = "desktop";

    /**
     * The ruleset that the file is based on.
     */
    private RulesetManagementInterface ruleset;

    /**
     * Backing bean for the structure panel.
     */
    private final StructurePanel structurePanel;

    /**
     * User sitting in front of the editor.
     */
    private User user;

    /**
     * The file content.
     */
    private Workpiece workpiece;

    /**
     * Original state of workpiece. Used to check whether any unsaved changes exist when leaving the editor.
     */
    private Workpiece workpieceOriginalState;

    /**
     * This List of Pairs stores all selected physical elements and the logical elements in which the physical element was selected.
     * It is necessary to store the logical elements as well, because a physical element can be assigned to multiple logical elements.
     */
    private List<Pair<PhysicalDivision, LogicalDivision>> selectedMedia;

    /**
     * The id of the template's task corresponding to the current task that is under edit.
     * This is used for saving and loading the metadata editor settings.
     * The current task, the corresponding template task id and the settings are only available
     * if the user opened the editor from a task.
     */
    private int templateTaskId;

    private DataEditorSetting dataEditorSetting;

    private static final String DESKTOP_LINK = "/pages/desktop.jsf";

    private List<PhysicalDivision> unsavedDeletedMedia = new ArrayList<>();

    private List<PhysicalDivision> unsavedUploadedMedia = new ArrayList<>();

    private boolean folderConfigurationComplete = false;

    /**
     * Public constructor.
     */
    public DataEditorForm() {
        this.structurePanel = new StructurePanel(this);
        this.metadataPanel = new MetadataPanel(this);
        this.galleryPanel = new GalleryPanel(this);
        this.paginationPanel = new PaginationPanel(this);
        this.addDocStrucTypeDialog = new AddDocStrucTypeDialog(this);
        this.addMetadataDialog = new AddMetadataDialog(this);
        this.addPhysicalDivisionDialog = new AddPhysicalDivisionDialog(this);
        this.changeDocStrucTypeDialog = new ChangeDocStrucTypeDialog(this);
        this.editPagesDialog = new EditPagesDialog(this);
        this.uploadFileDialog = new UploadFileDialog(this);
        acquisitionStage = "edit";
    }

    /**
     * Checks if the process is correctly set. Otherwise redirect to desktop,
     * because metadataeditor doesn't work without a process.
     */
    public void initMetadataEditor() {
        if (Objects.isNull(process)) {
            try {
                Helper.setErrorMessage("noProcessSelected");
                FacesContext context = FacesContext.getCurrentInstance();
                String path = context.getExternalContext().getRequestContextPath() + DESKTOP_LINK;
                context.getExternalContext().redirect(path);
            } catch (IOException e) {
                Helper.setErrorMessage("noProcessSelected");
            }
        }
    }

    /**
     * Open the metadata file of the process with the given ID in the metadata editor.
     *
     * @param processID
     *            ID of the process that is opened
     * @param referringView
     *            JSF page the user came from
     */
    public String open(String processID, String referringView) {
        try {
            this.referringView = referringView + ("processes".equals(referringView) ? "?keepPagination=true" : "");
            this.process = ServiceManager.getProcessService().getById(Integer.parseInt(processID));
            this.currentChildren.addAll(process.getChildren());
            this.user = ServiceManager.getUserService().getCurrentUser();
            this.checkProjectFolderConfiguration();
            this.loadDataEditorSettings();

            User blockedUser = MetadataLock.getLockUser(process.getId());
            if (Objects.nonNull(blockedUser) && !blockedUser.equals(this.user)) {
                Helper.setErrorMessage("blocked", blockedUser.getFullName());
                return referringView;
            }

            String metadataLanguage = user.getMetadataLanguage();
            priorityList = LanguageRange.parse(metadataLanguage.isEmpty() ? "en" : metadataLanguage);
            ruleset = ServiceManager.getRulesetService().openRuleset(process.getRuleset());
            openMetsFile();
            if (!workpiece.getId().equals(process.getId().toString())) {
                Helper.setErrorMessage("metadataConfusion", process.getId(), workpiece.getId());
                return referringView;
            }
            selectedMedia = new LinkedList<>();
            unsavedUploadedMedia = new ArrayList<>();
            init();
            MetadataLock.setLocked(process.getId(), user);
        } catch (IOException | DAOException | InvalidImagesException | NoSuchElementException e) {
            Helper.setErrorMessage(e.getLocalizedMessage(), logger, e);
            return referringView;
        }
        return "/pages/metadataEditor?faces-redirect=true";
    }

    private void checkProjectFolderConfiguration() {
        if (Objects.nonNull(this.process)) {
            Project project = this.process.getProject();
            if (Objects.nonNull(project)) {
                this.folderConfigurationComplete = Objects.nonNull(project.getGeneratorSource())
                        && Objects.nonNull(project.getMediaView()) && Objects.nonNull(project.getPreview());
            } else {
                this.folderConfigurationComplete = false;
            }
        } else {
            this.folderConfigurationComplete = false;
        }
    }

    private void loadDataEditorSettings() {
        if (templateTaskId > 0) {
            dataEditorSetting = ServiceManager.getDataEditorSettingService().loadDataEditorSetting(user.getId(),
                    templateTaskId);
            if (Objects.isNull(dataEditorSetting)) {
                dataEditorSetting = new DataEditorSetting();
                dataEditorSetting.setUserId(user.getId());
                dataEditorSetting.setTaskId(templateTaskId);
            }
        } else {
            dataEditorSetting = null;
        }
    }

    /**
     * Opens the METS file.
     *
     * @throws IOException
     *             if filesystem I/O fails
     */
    private void openMetsFile() throws IOException, InvalidImagesException {
        mainFileUri = ServiceManager.getProcessService().getMetadataFileUri(process);
        workpiece = ServiceManager.getMetsService().loadWorkpiece(mainFileUri);
        workpieceOriginalState = ServiceManager.getMetsService().loadWorkpiece(mainFileUri);
        if (Objects.isNull(workpiece.getId())) {
            logger.warn("Workpiece has no ID. Cannot verify workpiece ID. Setting workpiece ID.");
            workpiece.setId(process.getId().toString());
        }
        ServiceManager.getFileService().searchForMedia(process, workpiece);
    }

    private void init() {
        final long begin = System.nanoTime();

        List<PhysicalDivision> severalAssignments = new LinkedList<>();
        initSeveralAssignments(workpiece.getPhysicalStructure(), severalAssignments);
        structurePanel.getSeveralAssignments().addAll(severalAssignments);

        structurePanel.show();
        structurePanel.getSelectedLogicalNode().setSelected(true);
        structurePanel.getSelectedPhysicalNode().setSelected(true);
        metadataPanel.showLogical(getSelectedStructure());
        metadataPanel.showPhysical(getSelectedPhysicalDivision());
        galleryPanel.show();
        paginationPanel.show();

        editPagesDialog.prepare();

        if (logger.isTraceEnabled()) {
            logger.trace("Initializing editor beans took {} ms",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin));
        }
    }

    /**
     * Clears all remaining content from the data editor form.
     *
     * @return the referring view, to return there
     */
    public String close() {
        deleteNotSavedUploadedMedia();
        unsavedDeletedMedia.clear();
        metadataPanel.clear();
        structurePanel.clear();
        workpiece = null;
        workpieceOriginalState = null;
        mainFileUri = null;
        ruleset = null;
        currentChildren.clear();
        selectedMedia.clear();
        MetadataLock.setFree(process.getId());
        process = null;
        user = null;
        if (referringView.contains("?")) {
            return referringView + "&faces-redirect=true";
        } else {
            return referringView + "?faces-redirect=true";
        }
    }

    private void deleteUnsavedDeletedMedia() {
        URI uri = Paths.get(ConfigCore.getKitodoDataDirectory(),
                ServiceManager.getProcessService().getProcessDataDirectory(this.process).getPath()).toUri();
        for (PhysicalDivision physicalDivision : this.unsavedDeletedMedia) {
            for (URI fileURI : physicalDivision.getMediaFiles().values()) {
                try {
                    ServiceManager.getFileService().delete(uri.resolve(fileURI));
                } catch (IOException e) {
                    logger.error(e.getMessage());
                }
            }
        }
    }

    /**
     * Get unsavedDeletedMedia.
     *
     * @return value of unsavedDeletedMedia
     */
    public List<PhysicalDivision> getUnsavedDeletedMedia() {
        return unsavedDeletedMedia;
    }

    private void deleteNotSavedUploadedMedia() {
        URI uri = Paths.get(ConfigCore.getKitodoDataDirectory(),
                ServiceManager.getProcessService().getProcessDataDirectory(this.process).getPath()).toUri();
        for (PhysicalDivision mediaUnit : this.unsavedUploadedMedia) {
            for (URI fileURI : mediaUnit.getMediaFiles().values()) {
                try {
                    ServiceManager.getFileService().delete(uri.resolve(fileURI));
                } catch (IOException e) {
                    logger.error(e.getMessage());
                }
            }
        }
        this.unsavedUploadedMedia.clear();
    }

    /**
     * Validate the structure and metadata.
     *
     * @return whether the validation was successful or not
     */
    public boolean validate() {
        try {
            ValidationResult validationResult = ServiceManager.getMetadataValidationService().validate(workpiece,
                ruleset);
            State state = validationResult.getState();
            switch (state) {
                case ERROR:
                    Helper.setErrorMessage(Helper.getTranslation("dataEditor.validation.state.error"));
                    for (String message : validationResult.getResultMessages()) {
                        Helper.setErrorMessage(message);
                    }
                    return false;
                case WARNING:
                    Helper.setWarnMessage(Helper.getTranslation("dataEditor.validation.state.warning"));
                    for (String message : validationResult.getResultMessages()) {
                        Helper.setWarnMessage(message);
                    }
                    return true;
                default:
                    Helper.setMessage(Helper.getTranslation("dataEditor.validation.state.success"));
                    for (String message : validationResult.getResultMessages()) {
                        Helper.setMessage(message);
                    }
                    return true;
            }
        } catch (DAOException e) {
            Helper.setErrorMessage(e.getLocalizedMessage(), logger, e);
            return false;
        }
    }

    /**
     * Save the structure and metadata.
     */
    public void save() {
        save(false);
    }

    private String save(boolean close) {
        try {
            metadataPanel.preserve();
            structurePanel.preserve();
            ServiceManager.getProcessService().updateChildrenFromLogicalStructure(process, workpiece.getLogicalStructure());
            ServiceManager.getFileService().createBackupFile(process);
            try (OutputStream out = ServiceManager.getFileService().write(mainFileUri)) {
                ServiceManager.getMetsService().save(workpiece, out);
                ServiceManager.getProcessService().saveToIndex(process,false);
                unsavedUploadedMedia.clear();
                deleteUnsavedDeletedMedia();
                if (close) {
                    return close();
                } else {
                    PrimeFaces.current().executeScript("PF('notifications').renderMessage({'summary':'"
                            + Helper.getTranslation("metadataSaved") + "','severity':'info'})");
                }
            } catch (IOException e) {
                Helper.setErrorMessage(e.getLocalizedMessage(), logger, e);
            }
        } catch (Exception e) {
            Helper.setErrorMessage(e.getLocalizedMessage(), logger, e);
        }
        PrimeFaces.current().executeScript("PF('sticky-notifications').removeAll();");
        PrimeFaces.current().ajax().update("notifications");
        return null;
    }

    /**
     * Save the structure and metadata.
     *
     * @return navigation target
     */
    public String saveAndExit() {
        return save(true);
    }

    private void initSeveralAssignments(PhysicalDivision physicalDivision, List<PhysicalDivision> severalAssignments) {
        if (physicalDivision.getLogicalDivisions().size() > 1) {
            severalAssignments.add(physicalDivision);
        }
        for (PhysicalDivision child : physicalDivision.getChildren()) {
            initSeveralAssignments(child, severalAssignments);
        }
    }

    /**
     * Deletes the selected outline point from the logical outline. This method
     * is called by PrimeFaces to inform the application that the user has
     * clicked on the shortcut menu entry to clear the outline point.
     */
    public void deleteStructure() {
        structurePanel.deleteSelectedStructure();
    }

    /**
     * Deletes the selected physical division from the media list. The associated files
     * on the drive are not deleted. The next time the editor is started, files
     * that are not yet in the media list will be inserted there again. This
     * method is called by PrimeFaces to inform the application that the user
     * clicked on the context menu entry to delete the physical division.
     */
    public void deletePhysicalDivision() {
        structurePanel.deleteSelectedPhysicalDivision();
    }

    @Override
    public String getAcquisitionStage() {
        return acquisitionStage;
    }

    /**
     * Get unsavedUploadedMedia.
     *
     * @return value of unsavedUploadedMedia
     */
    public List<PhysicalDivision> getUnsavedUploadedMedia() {
        return unsavedUploadedMedia;
    }

    /**
     * Returns the backing bean for the add doc struc type dialog. This function
     * is used by PrimeFaces to access the elements of the add doc struc type
     * dialog.
     *
     * @return the backing bean for the add doc struc type dialog
     */
    public AddDocStrucTypeDialog getAddDocStrucTypeDialog() {
        return addDocStrucTypeDialog;
    }

    /**
     * Get addMetadataDialog.
     *
     * @return value of addMetadataDialog
     */
    public AddMetadataDialog getAddMetadataDialog() {
        return addMetadataDialog;
    }

    /**
     * Returns the backing bean for the add media dialog. This function is used
     * by PrimeFaces to access the elements of the add media dialog.
     *
     * @return the backing bean for the add media dialog
     */
    public AddPhysicalDivisionDialog getAddPhysicalDivisionDialog() {
        return addPhysicalDivisionDialog;
    }

    /**
     * Returns the backing bean for the change doc struc type dialog. This
     * function is used by PrimeFaces to access the elements of the change doc
     * struc type dialog.
     *
     * @return the backing bean for the change doc struc type dialog
     */
    public ChangeDocStrucTypeDialog getChangeDocStrucTypeDialog() {
        return changeDocStrucTypeDialog;
    }

    /**
     * Returns the backing bean for the edit pages dialog. This function is used
     * by PrimeFaces to access the elements of the edit pages dialog.
     *
     * @return the backing bean for the edit pages dialog
     */
    public EditPagesDialog getEditPagesDialog() {
        return editPagesDialog;
    }

    /**
     * Returns the backing bean for the gallery panel. This function is used by
     * PrimeFaces to access the elements of the gallery panel.
     *
     * @return the backing bean for the gallery panel
     */
    public GalleryPanel getGalleryPanel() {
        return galleryPanel;
    }

    Set<Process> getCurrentChildren() {
        return currentChildren;
    }

    /**
     * Returns the backing bean for the metadata panel. This function is used
     * by PrimeFaces to access the elements of the metadata panel.
     *
     * @return the backing bean for the metadata panel
     */
    public MetadataPanel getMetadataPanel() {
        return metadataPanel;
    }

    /**
     * Returns the backing bean for the pagination panel. This function is used
     * by PrimeFaces to access the elements of the pagination panel.
     *
     * @return the backing bean for the pagination panel
     */
    public PaginationPanel getPaginationPanel() {
        return paginationPanel;
    }

    @Override
    public List<LanguageRange> getPriorityList() {
        return priorityList;
    }

    /**
     * Get process.
     *
     * @return value of process
     */
    public Process getProcess() {
        return process;
    }

    /**
     * Get process title.
     *
     * @return value of process title
     */
    public String getProcessTitle() {
        return process.getTitle();
    }

    @Override
    public RulesetManagementInterface getRulesetManagement() {
        return ruleset;
    }

    public Optional<LogicalDivision> getSelectedStructure() {
        return structurePanel.getSelectedStructure();
    }

    Optional<PhysicalDivision> getSelectedPhysicalDivision() {
        return structurePanel.getSelectedPhysicalDivision();
    }

    /**
     * Check if the passed LogicalDivision is part of the selection.
     * @param structure LogicalDivision to be checked
     * @return boolean representing selection status
     */
    public boolean isStripeSelected(LogicalDivision structure) {
        Optional<LogicalDivision> selectedStructure = structurePanel.getSelectedStructure();
        return selectedStructure.filter(logicalDivision -> Objects.equals(structure, logicalDivision)).isPresent();
    }

    /**
     * Return structurePanel.
     *
     * @return structurePanel
     */
    public StructurePanel getStructurePanel() {
        return structurePanel;
    }

    Workpiece getWorkpiece() {
        return workpiece;
    }

    void refreshStructurePanel() {
        structurePanel.show(true);
        galleryPanel.updateStripes();
    }

    void setProcess(Process process) {
        this.process = process;
    }

    /**
     * Get selectedMedia.
     *
     * @return value of selectedMedia
     */
    public List<Pair<PhysicalDivision, LogicalDivision>> getSelectedMedia() {
        return selectedMedia;
    }

    /**
     * Checks and returns if consecutive physical divisions in one structure element are selected or not.
     * 
     * <p>Note: This method is called potentially thousands of times when rendering large galleries.</p>
     */
    public boolean consecutivePagesSelected() {
        if (selectedMedia.isEmpty()) {
            return false;
        }
        int maxOrder = selectedMedia.stream().mapToInt(m -> m.getLeft().getOrder()).max().orElseThrow(NoSuchElementException::new);
        int minOrder = selectedMedia.stream().mapToInt(m -> m.getLeft().getOrder()).min().orElseThrow(NoSuchElementException::new);

        // Check whether the set of selected media all belong to the same logical division, otherwise the selection 
        // is not consecutive. However, do not use stream().distinct(), which will do pairwise comparisons, which is 
        // slow for large amounts of selected images. Instead, just check whether the first logical division matches 
        // all others in a simple loop.
        Boolean theSameLogicalDivisions = true;
        LogicalDivision firstSelectedMediaLogicalDivison = null;
        for (Pair<PhysicalDivision, LogicalDivision> pair : selectedMedia) {
            if (Objects.isNull(firstSelectedMediaLogicalDivison)) {
                firstSelectedMediaLogicalDivison = pair.getRight();
            } else {
                if (!Objects.equals(firstSelectedMediaLogicalDivison, pair.getRight())) {
                    theSameLogicalDivisions = false;
                    break;
                }
            }
        }
        return selectedMedia.size() - 1 == maxOrder - minOrder && theSameLogicalDivisions;
    }

    void setSelectedMedia(List<Pair<PhysicalDivision, LogicalDivision>> media) {
        this.selectedMedia = media;
    }

    /**
     * Check if the passed PhysicalDivision is selected.
     * @param physicalDivision PhysicalDivision object to check for selection
     * @param logicalDivision object to check whether the PhysicalDivision is selected as a child of this LogicalDivision.
     *                                  A PhysicalDivision can be assigned to multiple logical divisionss but can be selected
     *                                  in one of these LogicalDivisions.
     * @return boolean whether the PhysicalDivision is selected at the specified position
     */
    public boolean isSelected(PhysicalDivision physicalDivision, LogicalDivision logicalDivision) {
        if (Objects.nonNull(physicalDivision) && Objects.nonNull(logicalDivision)) {
            return selectedMedia.contains(new ImmutablePair<>(physicalDivision, logicalDivision));
        }
        return false;
    }

    void switchStructure(Object treeNodeData, boolean updateGalleryAndPhysicalTree) throws NoSuchMetadataFieldException {
        try {
            metadataPanel.preserveLogical();
        } catch (InvalidMetadataValueException e) {
            logger.info(e.getLocalizedMessage(), e);
        }

        Optional<LogicalDivision> selectedStructure = structurePanel.getSelectedStructure();

        metadataPanel.showLogical(selectedStructure);
        if (treeNodeData instanceof StructureTreeNode) {
            StructureTreeNode structureTreeNode = (StructureTreeNode) treeNodeData;
            if (Objects.nonNull(structureTreeNode.getDataObject())) {
                if (structureTreeNode.getDataObject() instanceof LogicalDivision
                        && selectedStructure.isPresent()) {
                    // Logical structure element selected
                    if (structurePanel.isSeparateMedia()) {
                        LogicalDivision structuralElement = selectedStructure.get();
                        if (!structuralElement.getViews().isEmpty()) {
                            ArrayList<View> views = new ArrayList<>(structuralElement.getViews());
                            if (Objects.nonNull(views.get(0)) && updateGalleryAndPhysicalTree) {
                                updatePhysicalStructureTree(views.get(0));
                                updateGallery(views.get(0));
                            }
                        } else {
                            updatePhysicalStructureTree(null);
                        }
                    } else {
                        getSelectedMedia().clear();
                    }
                } else if (structureTreeNode.getDataObject() instanceof View) {
                    // Page selected in logical tree
                    View view = (View) structureTreeNode.getDataObject();
                    metadataPanel.showPageInLogical(view.getPhysicalDivision());
                    if (updateGalleryAndPhysicalTree) {
                        updateGallery(view);
                    }
                    // no need to update physical tree because pages can only be clicked in logical tree if physical tree is hidden!
                }
            }
        }
        paginationPanel.preparePaginationSelectionSelectedItems();
    }

    void switchPhysicalDivision() throws NoSuchMetadataFieldException {
        try {
            metadataPanel.preservePhysical();
        } catch (InvalidMetadataValueException e) {
            logger.info(e.getLocalizedMessage(), e);
        }

        Optional<PhysicalDivision> selectedPhysicalDivision = structurePanel.getSelectedPhysicalDivision();

        metadataPanel.showPhysical(selectedPhysicalDivision);
        if (selectedPhysicalDivision.isPresent()) {
            // update gallery
            galleryPanel.updateSelection(selectedPhysicalDivision.get(), null);
            // update logical tree
            for (GalleryMediaContent galleryMediaContent : galleryPanel.getMedias()) {
                if (Objects.nonNull(galleryMediaContent.getView())
                        && Objects.equals(selectedPhysicalDivision.get(), galleryMediaContent.getView().getPhysicalDivision())) {
                    structurePanel.updateLogicalNodeSelection(galleryMediaContent, null);
                    break;
                }
            }
        }
    }

    private void updatePhysicalStructureTree(View view) {
        GalleryMediaContent galleryMediaContent = this.galleryPanel.getGalleryMediaContent(view);
        structurePanel.updatePhysicalNodeSelection(galleryMediaContent);
    }

    private void updateGallery(View view) {
        PhysicalDivision physicalDivision = view.getPhysicalDivision();
        if (Objects.nonNull(physicalDivision)) {
            galleryPanel.updateSelection(physicalDivision, structurePanel.getPageStructure(view, workpiece.getLogicalStructure()));
        }
    }

    void assignView(LogicalDivision logicalDivision, View view, Integer index) {
        if (Objects.nonNull(index) && index >= 0 && index < logicalDivision.getViews().size()) {
            logicalDivision.getViews().add(index, view);
        } else {
            logicalDivision.getViews().add(view);
        }
        view.getPhysicalDivision().getLogicalDivisions().add(logicalDivision);
    }

    void unassignView(LogicalDivision logicalDivision, View view, boolean removeLast) {
        // if View was moved within one element, we need to distinguish two possible directions it could have been moved
        if (removeLast) {
            logicalDivision.getViews().removeLastOccurrence(view);
        } else {
            logicalDivision.getViews().removeFirstOccurrence(view);
        }
        view.getPhysicalDivision().getLogicalDivisions().remove(logicalDivision);
    }

    /**
     * Retrieve and return 'title' value of given Object 'dataObject' if Object is instance of
     * 'LogicalDivision' and if it does have a title. Uses a configurable list of metadata keys to determine
     * which metadata keys should be considered.
     * Return empty string otherwise.
     *
     * @param dataObject
     *          StructureTreeNode containing the LogicalDivision whose title is returned
     * @return 'title' value of the LogicalDivision contained in the given StructureTreeNode 'treeNode'
     */
    public String getStructureElementTitle(Object dataObject) {
        if (dataObject instanceof LogicalDivision) {
            return DataEditorService.getTitleValue((LogicalDivision) dataObject, structurePanel.getTitleMetadata());
        }
        return "";
    }

    /**
     * Get referringView.
     *
     * @return value of referringView
     */
    public String getReferringView() {
        return referringView;
    }

    /**
     * Check and return whether the given ProcessDetail 'processDetail' is contained in the current list of addable
     * metadata types in the addDocStrucTypeDialog.
     *
     * @param treeNode treeNode to be added
     * @return whether the given ProcessDetail can be added or not
     */
    public boolean canBeAdded(TreeNode treeNode) {
        if (Objects.isNull(treeNode.getParent().getParent())) {
            if (Objects.nonNull(metadataPanel.getSelectedMetadataTreeNode()) || Objects.isNull(addMetadataDialog.getAddableMetadata())) {
                this.addMetadataDialog.prepareAddableMetadataForStructure(treeNode.getParent().getChildren());
            }
        } else if (!Objects.equals(metadataPanel.getSelectedMetadataTreeNode(), treeNode.getParent())
                || Objects.isNull(addMetadataDialog.getAddableMetadata())) {
            prepareAddableMetadataForGroup(treeNode.getParent());
        }
        if (Objects.nonNull(addMetadataDialog.getAddableMetadata())) {
            return addMetadataDialog.getAddableMetadata().stream()
                    .map(SelectItem::getValue).collect(Collectors.toList()).contains(((ProcessDetail) treeNode.getData()).getMetadataID());
        }
        return false;
    }

    @Override
    public boolean canBeDeleted(ProcessDetail processDetail) {
        return processDetail.getOccurrences() > 1 && processDetail.getOccurrences() > processDetail.getMinOcc()
                || (!processDetail.isRequired() && !this.ruleset.isAlwaysShowingForKey(processDetail.getMetadataID()));
    }

    /**
     * Check for changes in workpiece.
     */
    public void checkForChanges() {
        if (Objects.nonNull(PrimeFaces.current())) {
            boolean unsavedChanges = !this.workpiece.equals(workpieceOriginalState);
            PrimeFaces.current().executeScript("setConfirmUnload(" + unsavedChanges + ");");
        }
    }

    /**
     * Get the shortcuts for the current user.
     *
     * @return shortcuts as java.lang.String
     */
    public String getShortcuts() {
        try {
            return ServiceManager.getUserService().getShortcuts(user.getId());
        } catch (DAOException e) {
            Helper.setErrorMessage(e.getLocalizedMessage(), logger, e);
            return "{}";
        }
    }

    /**
     * Get templateTaskId.
     *
     * @return value of templateTaskId
     */
    public int getTemplateTaskId() {
        return templateTaskId;
    }

    /**
     * Set templateTaskId.
     *
     * @param templateTaskId as int
     */
    public void setTemplateTaskId(int templateTaskId) {
        this.templateTaskId = templateTaskId;
    }

    /**
     * Get dataEditorSetting.
     *
     * @return value of dataEditorSetting
     */
    public DataEditorSetting getDataEditorSetting() {
        return dataEditorSetting;
    }

    /**
     * Set dataEditorSetting.
     *
     * @param dataEditorSetting as org.kitodo.data.database.beans.DataEditorSetting
     */
    public void setDataEditorSetting(DataEditorSetting dataEditorSetting) {
        this.dataEditorSetting = dataEditorSetting;
    }

    /**
     * Save current metadata editor layout.
     */
    public void saveDataEditorSetting() {
        if (Objects.nonNull(dataEditorSetting) && dataEditorSetting.getTaskId() > 0) {
            try {
                ServiceManager.getDataEditorSettingService().saveToDatabase(dataEditorSetting);
            } catch (DAOException e) {
                Helper.setErrorMessage("errorSaving", new Object[] {ObjectType.USER.getTranslationSingular() }, logger, e);
            }
        } else {
            logger.error("Could not save DataEditorSettings with userId {} and templateTaskId {}", user.getId(),
                templateTaskId);
        }
    }

    /**
     * Get uploadFileDialog.
     *
     * @return value of uploadFileDialog
     */
    public UploadFileDialog getUploadFileDialog() {
        return uploadFileDialog;
    }

    /**
     * Get folderConfigurationComplete.
     *
     * @return value of folderConfigurationComplete
     */
    public boolean isFolderConfigurationComplete() {
        return folderConfigurationComplete;
    }

    /**
     * Preserve changes in the metadata panel.
     */
    public void preserveMetadataPanel() {
        try {
            metadataPanel.preserve();
        } catch (InvalidMetadataValueException | NoSuchMetadataFieldException e) {
            logger.info(e.getMessage());
        }
    }

    /**
     * Check and return whether given TreeNode contains ProcessFieldedMetadata and if any further metadata can
     * be added to it or not.
     *
     * @return whether given TreeNode contains ProcessFieldedMetadata and if any further metadata can be added to it
     */
    public boolean metadataAddableToGroup(TreeNode metadataNode) {
        return metadataPanel.metadataAddableToGroup(metadataNode);
    }

    /**
     * Prepare addable metadata for metadata group.
     */
    public void prepareAddableMetadataForGroup(TreeNode treeNode) {
        addMetadataDialog.prepareAddableMetadataForGroup(treeNode);
    }
}
