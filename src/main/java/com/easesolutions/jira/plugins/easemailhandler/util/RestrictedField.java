package com.easesolutions.jira.plugins.easemailhandler.util;


import java.util.List;

public class RestrictedField
{
    private String fieldConfigurationSchemeIdString;
    private String fieldLowerName;
    private String fieldId;
    private String allowReporter;
    private List<String> allowedProjectRoles;


    public String getFieldConfigurationSchemeIdString() {
        return fieldConfigurationSchemeIdString;
    }

    public void setFieldConfigurationSchemeIdString(String fieldConfigurationSchemeIdString) {
        this.fieldConfigurationSchemeIdString = fieldConfigurationSchemeIdString;
    }

    public String getFieldLowerName() {
        return fieldLowerName;
    }

    public void setFieldLowerName(String fieldLowerName) {
        this.fieldLowerName = fieldLowerName;
    }

    public String getFieldId() {
        return fieldId;
    }

    public void setFieldId(String fieldId) {
        this.fieldId = fieldId;
    }

    public String getAllowReporter() {
        return allowReporter;
    }

    public void setAllowReporter(String allowReporter) {
        this.allowReporter = allowReporter;
    }

    public List<String> getAllowedProjectRoles() {
        return allowedProjectRoles;
    }

    public void setAllowedProjectRoles(List<String> allowedProjectRoles) {
        this.allowedProjectRoles = allowedProjectRoles;
    }
}