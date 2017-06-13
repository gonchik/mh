package com.easesolutions.jira.plugins.easemailhandler.admin.web;


import com.atlassian.jira.config.properties.ApplicationProperties;
import com.atlassian.jira.security.Permissions;
import com.atlassian.jira.web.action.JiraWebActionSupport;
import com.easesolutions.jira.plugins.easemailhandler.util.ConfigUtil;

import java.util.ArrayList;
import java.util.List;

public class EaseMailHandlerConfigAction extends JiraWebActionSupport {

    private static final String SECURITY_BREACH = "securitybreach";

    private String compulsoryFields;
    private String restrictions;
    private String assigneeOption;
    private String defaultProject;
    private String defaultReporter;
    private String senderEmailCfId;
    private String toFieldCfId;
    private String ccFieldCfId;




    public String getCompulsoryFields() {
        return compulsoryFields;
    }

    public void setCompulsoryFields(String compulsoryFields) {
        this.compulsoryFields = compulsoryFields;
    }

    public String getRestrictions() {
        return restrictions;
    }

    public void setRestrictions(String restrictions) {
        this.restrictions = restrictions;
    }

    public String getAssigneeOption()
    {
        return assigneeOption;
    }

    public void setAssigneeOption(String assigneeOption)
    {
        this.assigneeOption = assigneeOption;
    }

    @Override
    protected String doExecute() throws Exception {
        return doView();
    }

    public String doView()
    {

        if(!hasAdminRights())
        {
            return SECURITY_BREACH;
        }

        loadValue();
        return SUCCESS;
    }

    public String doEdit()
    {
        if(!hasAdminRights())
        {
            return SECURITY_BREACH;
        }
        saveValue();
        return getRedirect("/secure/EaseMailHandlerConfig!view.jspa");
    }





    private boolean hasAdminRights()
    {
        return isHasPermission(Permissions.ADMINISTER);
    }

    private void loadValue()
    {
        ApplicationProperties properties = getApplicationProperties();
        setCompulsoryFields(ConfigUtil.getString(properties, ConfigUtil.KEY_COMPULSORY_FIELDS,""));
        setRestrictions(ConfigUtil.getString(properties, ConfigUtil.KEY_RESTRICTED_FIELDS,""));
        setAssigneeOption(ConfigUtil.getString(properties,ConfigUtil.KEY_DEFAULTASSIGNEE_FOR_CREATE_ISSUE,""));
        setDefaultProject(ConfigUtil.getString(properties,ConfigUtil.KEY_DEFAULT_PROJECT,""));
        setDefaultReporter(ConfigUtil.getString(properties,ConfigUtil.KEY_DEFAULT_REPORTER,""));
        setSenderEmailCfId(ConfigUtil.getString(properties,ConfigUtil.KEY_SENDER_EMAIL_CF,""));
        setToFieldCfId(ConfigUtil.getString(properties,ConfigUtil.KEY_TO_CF,""));
        setCcFieldCfId(ConfigUtil.getString(properties, ConfigUtil.KEY_TO_CF, ""));
    }

    private void saveValue()
    {
        ApplicationProperties applicationProperties = getApplicationProperties();
        applicationProperties.setString(ConfigUtil.KEY_COMPULSORY_FIELDS, compulsoryFields.toLowerCase());
        applicationProperties.setString(ConfigUtil.KEY_RESTRICTED_FIELDS, restrictions.toLowerCase());
        applicationProperties.setString(ConfigUtil.KEY_DEFAULTASSIGNEE_FOR_CREATE_ISSUE,assigneeOption.toLowerCase());
        applicationProperties.setString(ConfigUtil.KEY_DEFAULT_PROJECT, defaultProject.trim());
        applicationProperties.setString(ConfigUtil.KEY_DEFAULT_REPORTER, defaultReporter.trim());
        applicationProperties.setString(ConfigUtil.KEY_SENDER_EMAIL_CF, senderEmailCfId.trim());
        applicationProperties.setString(ConfigUtil.KEY_TO_CF, toFieldCfId.trim());
        applicationProperties.setString(ConfigUtil.KEY_CC_CF, ccFieldCfId.trim());
    }


    public String getDefaultProject()
    {
        return defaultProject;
    }

    public void setDefaultProject(String defaultProject)
    {
        this.defaultProject = defaultProject;
    }

    public String getDefaultReporter()
    {
        return defaultReporter;
    }

    public void setDefaultReporter(String defaultReporter)
    {
        this.defaultReporter = defaultReporter;
    }

    public String getSenderEmailCfId()
    {
        return senderEmailCfId;
    }

    public void setSenderEmailCfId(String senderEmailCfId)
    {
        this.senderEmailCfId = senderEmailCfId;
    }

    public String getToFieldCfId()
    {
        return toFieldCfId;
    }

    public void setToFieldCfId(String toFieldCfId)
    {
        this.toFieldCfId = toFieldCfId;
    }

    public String getCcFieldCfId()
    {
        return ccFieldCfId;
    }

    public void setCcFieldCfId(String ccFieldCfId)
    {
        this.ccFieldCfId = ccFieldCfId;
    }
}
