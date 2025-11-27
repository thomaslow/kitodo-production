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

import java.text.MessageFormat;

import jakarta.annotation.PostConstruct;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kitodo.data.database.beans.Ruleset;
import org.kitodo.data.database.exceptions.DAOException;
import org.kitodo.production.enums.ObjectType;
import org.kitodo.production.helper.Helper;
import org.kitodo.production.model.LazyBeanModel;
import org.kitodo.production.services.ServiceManager;
import org.primefaces.model.SortMeta;
import org.primefaces.model.SortOrder;

@Named("RulesetListView")
@ViewScoped
public class RulesetListView extends BaseForm {
    
    public static final String VIEW_PATH = MessageFormat.format(REDIRECT_PATH, "projects") + "#rulesetTab";

    private static final Logger logger = LogManager.getLogger(RulesetListView.class);

    /**
     * Initialize RulesetListView.
     */
    @PostConstruct
    public void init() {
        setLazyBeanModel(new LazyBeanModel(ServiceManager.getRulesetService()));
        sortBy = SortMeta.builder().field("title.keyword").order(SortOrder.ASCENDING).build();
    }

    /**
     * Initialize new Ruleset.
     *
     * @return page
     */
    public String createNewRuleset() {
        return RulesetEditView.VIEW_PATH;
    }

    /**
     * Delete ruleset.
     */
    public void delete(Ruleset ruleset) {
        try {
            if (hasAssignedProcessesOrTemplates(ruleset.getId())) {
                Helper.setErrorMessage("rulesetInUse");
            } else {
                ServiceManager.getRulesetService().remove(ruleset);
            }
        } catch (DAOException e) {
            Helper.setErrorMessage(ERROR_DELETING, new Object[] {ObjectType.RULESET.getTranslationSingular() }, logger,
                    e);
        }
    }

    private boolean hasAssignedProcessesOrTemplates(int rulesetId) throws DAOException {
        return !ServiceManager.getProcessService().findByRuleset(rulesetId).isEmpty()
                || !ServiceManager.getTemplateService().findByRuleset(rulesetId).isEmpty();
    }

}
