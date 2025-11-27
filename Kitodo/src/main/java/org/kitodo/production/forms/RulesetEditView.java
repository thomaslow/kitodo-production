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
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.annotation.PostConstruct;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kitodo.api.dataeditor.rulesetmanagement.FunctionalMetadata;
import org.kitodo.api.dataeditor.rulesetmanagement.RulesetManagementInterface;
import org.kitodo.config.ConfigCore;
import org.kitodo.config.enums.ParameterCore;
import org.kitodo.data.database.beans.Ruleset;
import org.kitodo.data.database.exceptions.DAOException;
import org.kitodo.production.enums.ObjectType;
import org.kitodo.production.helper.Helper;
import org.kitodo.production.services.ServiceManager;
import org.kitodo.production.services.data.RulesetService;

@Named("RulesetEditView")
@ViewScoped
public class RulesetEditView extends BaseForm {
    
    public static final String VIEW_PATH = MessageFormat.format(REDIRECT_PATH, "rulesetEdit");
    
    private Ruleset ruleset;
    private static final Logger logger = LogManager.getLogger(RulesetEditView.class);
    private static final String AT_MARK = "@";

    /**
     * Initialize RulesetEditView.
     */
    @PostConstruct
    public void init() {
        this.ruleset = new Ruleset();
        this.ruleset.setClient(ServiceManager.getUserService().getSessionClientOfAuthenticatedUser());
    }

    /**
     * Getter.
     */
    public Ruleset getRuleset() {
        return this.ruleset;
    }

    /**
     * Method being used as viewAction for ruleset edit form. If given parameter
     * 'id' is '0', the form for creating a new ruleset will be displayed.
     *
     * @param id
     *            ID of the ruleset to load
     */
    public void load(int id) {
        try {
            if (!Objects.equals(id, 0)) {
                this.ruleset = ServiceManager.getRulesetService().getById(id);
            }
            setSaveDisabled(true);
        } catch (DAOException e) {
            Helper.setErrorMessage(ERROR_LOADING_ONE, new Object[] {ObjectType.RULESET.getTranslationSingular(), id }, logger, e);
        }
    }

    /**
     * Save.
     *
     * @return page or empty String
     */
    public String save() {
        try {
            if (hasValidRulesetFilePath(this.ruleset, ConfigCore.getParameter(ParameterCore.DIR_RULESETS))) {
                if (ServiceManager.getRulesetService().existsRulesetWithSameName(this.ruleset)) {
                    Helper.setErrorMessage("rulesetTitleDuplicated");
                    return this.stayOnCurrentPage;
                }
                ServiceManager.getRulesetService().save(this.ruleset);
                return RulesetListView.VIEW_PATH;
            } else {
                Helper.setErrorMessage("rulesetNotFound", new Object[] {this.ruleset.getFile()});
                return this.stayOnCurrentPage;
            }
        } catch (DAOException e) {
            Helper.setErrorMessage(ERROR_SAVING, new Object[] {ObjectType.RULESET.getTranslationSingular() }, logger,
                e);
            return this.stayOnCurrentPage;
        }
    }

    /**
     * Get list of ruleset filenames.
     *
     * @return list of ruleset filenames
     */
    public List<Path> getRulesetFilenames() {
        try (Stream<Path> rulesetPaths = Files.walk(Paths.get(ConfigCore.getParameter(ParameterCore.DIR_RULESETS)), 1)) {
            return rulesetPaths.filter(f -> f.toString().endsWith(".xml")).map(Path::getFileName).sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            Helper.setErrorMessage(ERROR_LOADING_MANY, new Object[] {ObjectType.RULESET.getTranslationPlural() },
                logger, e);
            return new ArrayList<>();
        }
    }

    /**
     * Retrieve and return collection of metadata keys for functional metadata of given type "functionalMetadata" from current ruleset.
     * @param functionalMetadata type of functional metadata
     * @return collection of strings containing metadata keys
     */
    public Collection<String> getFunctionalMetadataKeys(FunctionalMetadata functionalMetadata) {
        try {
            return RulesetService.getFunctionalMetadataKeys(ruleset, functionalMetadata);
        } catch (IOException e) {
            Helper.setErrorMessage(e.getLocalizedMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Retrieve and return translated description text for functional metadata of given type "functionalMetadataType".
     * @param functionalMetadataType type of functional metadata for which description is returned
     * @return translated description of functional metadata
     */
    public String getFunctionalMetadataDescription(String functionalMetadataType) {
        return Helper.getTranslation("functionalMetadata." +  functionalMetadataType);
    }

    /**
     * Retrieve label of metadata with key 'metadataKey' from current ruleset.
     *
     * @param metadataKey key of metadata for which label is retrieved from current ruleset
     * @return label of metadata with key 'metadataKey'
     */
    public String getMetadataLabel(String metadataKey) {
        try {
            RulesetManagementInterface rulesetManagement =  ServiceManager.getRulesetService().openRuleset(ruleset);
            return ServiceManager.getRulesetService().getMetadataTranslation(rulesetManagement, metadataKey, AT_MARK);
        } catch (IOException e) {
            Helper.setErrorMessage(e.getLocalizedMessage());
            return "";
        }
    }

    /**
     * Checks that ruleset file exists.
     *
     * @param ruleset
     *            ruleset
     * @param pathToRulesets
     *            path to ruleset
     * @return true if ruleset file exists
     */
    private boolean hasValidRulesetFilePath(Ruleset ruleset, String pathToRulesets) {
        File rulesetFile = new File(pathToRulesets + ruleset.getFile());
        return rulesetFile.exists();
    }
}
