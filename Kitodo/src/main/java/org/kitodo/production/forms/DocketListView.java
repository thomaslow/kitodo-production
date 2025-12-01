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
import org.kitodo.data.database.beans.Docket;
import org.kitodo.data.database.exceptions.DAOException;
import org.kitodo.production.enums.ObjectType;
import org.kitodo.production.helper.Helper;
import org.kitodo.production.model.LazyBeanModel;
import org.kitodo.production.services.ServiceManager;
import org.primefaces.model.SortMeta;
import org.primefaces.model.SortOrder;

@Named("DocketListView")
@ViewScoped
public class DocketListView extends BaseForm {
    public static final String VIEW_PATH = MessageFormat.format(REDIRECT_PATH, "projects") + "#docketTab";
    
    private static final Logger logger = LogManager.getLogger(DocketListView.class);

    /**
     * Initialize DocketListView.
     */
    @PostConstruct
    public void init() {
        setLazyBeanModel(new LazyBeanModel(ServiceManager.getDocketService()));
        sortBy = SortMeta.builder().field("title.keyword").order(SortOrder.ASCENDING).build();
    }

    /**
     * Creates a new Docket.
     *
     * @return the navigation String
     */
    public String newDocket() {
        return DocketEditView.VIEW_PATH;
    }

    /**
     * Delete docket.
     */
    public void delete(Docket docket) {
        try {
            if (hasAssignedProcessesOrTemplates(docket.getId())) {
                Helper.setErrorMessage("docketInUse");
            } else {
                ServiceManager.getDocketService().remove(docket);
            }
        } catch (DAOException e) {
            Helper.setErrorMessage(ERROR_DELETING, new Object[] {ObjectType.DOCKET.getTranslationSingular() }, logger,
                e);
        }
    }

    /**
     * Checks whether a docket is currently used by any process or template.
     * 
     * @param docketId the id of the docket to check
     * @return true if docket is currently used by any process or template
     * @throws DAOException if checking fails
     */
    private boolean hasAssignedProcessesOrTemplates(int docketId) throws DAOException {
        return !ServiceManager.getProcessService().findByDocket(docketId).isEmpty()
                || !ServiceManager.getTemplateService().findByDocket(docketId).isEmpty();
    }

}
