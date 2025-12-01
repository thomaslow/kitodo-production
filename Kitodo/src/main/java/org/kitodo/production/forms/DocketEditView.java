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

package org.kitodo.production.forms;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.annotation.PostConstruct;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kitodo.config.ConfigCore;
import org.kitodo.config.enums.ParameterCore;
import org.kitodo.data.database.beans.Docket;
import org.kitodo.data.database.exceptions.DAOException;
import org.kitodo.production.enums.ObjectType;
import org.kitodo.production.helper.Helper;
import org.kitodo.production.services.ServiceManager;

@Named("DocketEditView")
@ViewScoped
public class DocketEditView extends BaseForm {
    public static final String VIEW_PATH = MessageFormat.format(REDIRECT_PATH, "docketEdit");
    
    private static final Logger logger = LogManager.getLogger(DocketEditView.class);

    private Docket docket;
    private List<Path> docketFiles;

    /**
     * Initialize DocketEditView.
     */
    @PostConstruct
    public void init() {
        this.docket = new Docket();
        this.docket.setClient(ServiceManager.getUserService().getSessionClientOfAuthenticatedUser());
        this.docketFiles = loadDocketFiles();
    }

    /**
     * Getter docket.
     *
     * @return Docket object
     */
    public Docket getDocket() {
        return this.docket;
    }

    /**
     * Get list of all available docket filenames.
     *
     * @return list of docket filenames
     */
    public List<Path> getDocketFiles() {
        return docketFiles;
    }

    /**
     * Method being used as viewAction for docket edit form.
     *
     * @param id
     *            ID of the docket to load
     */
    public void load(int id) {
        try {
            if (!Objects.equals(id, 0)) {
                this.docket = ServiceManager.getDocketService().getById(id);
            }
            setSaveDisabled(true);
        } catch (DAOException e) {
            Helper.setErrorMessage(ERROR_LOADING_ONE, new Object[] {ObjectType.DOCKET.getTranslationSingular(), id },
                logger, e);
        }
    }

    /**
     * Save docket.
     *
     * @return page or empty String
     */
    public String save() {
        try {
            if (hasValidRulesetFilePath(docket, ConfigCore.getParameter(ParameterCore.DIR_XSLT))) {
                if (existsDocketWithSameName()) {
                    Helper.setErrorMessage("docketTitleDuplicated");
                    return this.stayOnCurrentPage;
                }
                ServiceManager.getDocketService().save(docket);
                return DocketListView.VIEW_PATH;
            } else {
                Helper.setErrorMessage("docketNotFound");
                return this.stayOnCurrentPage;
            }
        } catch (DAOException e) {
            Helper.setErrorMessage(ERROR_SAVING, new Object[] {ObjectType.DOCKET.getTranslationSingular() }, logger, e);
            return this.stayOnCurrentPage;
        }
    }

    /**
     * Checks whether a ruleset file exists on the filesystem for a docket.
     * 
     * @param d the docket to check
     * @param pathToRulesets the path to ruleset files
     * @return true if ruleset file for docket exists
     */
    private boolean hasValidRulesetFilePath(Docket d, String pathToRulesets) {
        File rulesetFile = new File(pathToRulesets + d.getFile());
        return rulesetFile.exists();
    }

    /**
     * Checks whether a docket with the same title already exists.
     * 
     * @return true if a docket with the same title already exists
     */
    private boolean existsDocketWithSameName() {
        List<Docket> dockets = ServiceManager.getDocketService().getByTitle(this.docket.getTitle());
        if (dockets.isEmpty()) {
            return false;
        } else {
            if (Objects.nonNull(this.docket.getId())) {
                if (dockets.size() == 1) {
                    return !dockets.get(0).getId().equals(this.docket.getId());
                } else {
                    return true;
                }
            } else {
                return true;
            }
        }
    }

    /**
     * Load the lost of all available docket files from file system.
     * 
     * @return the list of all available docket files
     */
    private List<Path> loadDocketFiles() {
        try (Stream<Path> docketPaths = Files.walk(Paths.get(ConfigCore.getParameter(ParameterCore.DIR_XSLT)))) {
            return docketPaths.filter(s -> s.toString().endsWith(".xsl")).map(Path::getFileName).sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            Helper.setErrorMessage(ERROR_LOADING_MANY, new Object[] {ObjectType.DOCKET.getTranslationPlural() }, logger,
                e);
            return new ArrayList<>();
        }
    }

}
