package com.easesolutions.jira.plugins.easemailhandler.util;


import com.atlassian.jira.ComponentManager;
import com.atlassian.jira.config.properties.ApplicationProperties;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public class ConfigUtil {

    public static final String KEY_COMPULSORY_FIELDS = "com_easesolutions_jira_plugins_easemailhandler_projectcompulsoryfieldssetting";
    public static final String KEY_RESTRICTED_FIELDS = "com_easesolutions_jira_plugins_easemailhandler_projectrestrictedfieldssetting";
    public static final String KEY_DEFAULTASSIGNEE_FOR_CREATE_ISSUE = "com_easesolutions_jira_plugins_easemailhandler_DEFAULTASSIGNEE";
    public static final String KEY_DEFAULT_PROJECT = "com_easesolutions_jira_plugins_easemailhandler_KEY_DEFAULT_PROJECT";
    public static final String KEY_DEFAULT_REPORTER = "com_easesolutions_jira_plugins_easemailhandler_KEY_DEFAULT_REPORTER";
    public static final String KEY_SENDER_EMAIL_CF = "com_easesolutions_jira_plugins_easemailhandler_KEY_SENDER_EMAIL_CF";
    public static final String KEY_TO_CF = "com_easesolutions_jira_plugins_easemailhandler_KEY_TO_CF";
    public static final String KEY_CC_CF = "com_easesolutions_jira_plugins_easemailhandler_KEY_CC_CF";


    private HashMap<String,List<String>> projectCompulsoryFields = new HashMap<String, List<String>>();
    private HashMap<String,List<RestrictedField>> projectsRestrictedFields = new HashMap<String, List<RestrictedField>>();
    private HashMap<String,String> wkfSchemeIdAssigneeMapping = new HashMap<String, String>();//workflow scheme id - default assignee when create

    public static final String MAJOR_DELIMITER = ":";
    public static final String MIDDLE_DELIMITER = ";";
    public static final String MINOR_DELIMITER = ",";



    public ConfigUtil()
    {
        loadSettings();
    }



    public HashMap<String, List<String>> getProjectCompulsoryFields() {
        return projectCompulsoryFields;
    }

    public HashMap<String, List<RestrictedField>> getProjectsRestrictedFields() {
        return projectsRestrictedFields;
    }

    public HashMap<String, String> getWkfSchemeIdAssigneeMapping() {
        return wkfSchemeIdAssigneeMapping;
    }


    public void loadSettings()
    {

         ApplicationProperties ap = ComponentManager.getInstance().getApplicationProperties();


        
        //parse project - compulsory fields
        String compulsoryFields = getString(ap,KEY_COMPULSORY_FIELDS ,"").toLowerCase();
        String[] compulsoryFieldsArray = compulsoryFields.split(MAJOR_DELIMITER);
        for(String ele : compulsoryFieldsArray)
        {
            String[] map = ele.split(MIDDLE_DELIMITER);
            if(map.length==2)
            {
                String fieldConfigurationScheme = map[0].trim();
                String[] fieldIds = map[1].trim().split(MINOR_DELIMITER);
                List<String> fieldsNameList = trimStringList(Arrays.asList(fieldIds));
                projectCompulsoryFields.put(fieldConfigurationScheme,fieldsNameList);
            }
        }

        //parse restricted fields
        String restricted = getString(ap,KEY_RESTRICTED_FIELDS ,"").toLowerCase();
        String[] restrictedFieldsArray = restricted.split(MAJOR_DELIMITER);
        for(String ele : restrictedFieldsArray)
        {
            String[] map = ele.split(MIDDLE_DELIMITER);
            if(map.length==6)
            {
                String fieldConfigurationSchemeId = map[0].trim();
                String fieldName = map[1].trim();
                String fieldId = map[2].trim();
                String allowReporter = map[3].trim(); //"true"
                List<String> allowedRoleNames = trimStringList(Arrays.asList(map[4].trim().split(MINOR_DELIMITER)));

                RestrictedField restrictedField = new RestrictedField();
                restrictedField.setFieldConfigurationSchemeIdString(fieldConfigurationSchemeId);
                restrictedField.setFieldLowerName(fieldName);
                restrictedField.setFieldId(fieldId);
                restrictedField.setAllowReporter(allowReporter);
                restrictedField.setAllowedProjectRoles(allowedRoleNames);
                if(projectsRestrictedFields.containsKey(fieldConfigurationSchemeId))
                {
                    projectsRestrictedFields.get(fieldConfigurationSchemeId).add(restrictedField);
                }
                else
                {
                    List<RestrictedField> rfList = new ArrayList<RestrictedField>();
                    rfList.add(restrictedField);
                    projectsRestrictedFields.put(fieldConfigurationSchemeId,rfList);
                }
            }
        }

        //parse the workflow scheme id and assignee option
        String wkfAssignee = getString(ap,KEY_DEFAULTASSIGNEE_FOR_CREATE_ISSUE,"").toLowerCase();
        String[] wkfAssigneePairs = wkfAssignee.split(MAJOR_DELIMITER);
        for(String wkfAssigneeP : wkfAssigneePairs)
        {
            String[] elements = wkfAssigneeP.split(MIDDLE_DELIMITER);
            if(elements.length==2)
            {
                String wkfSchemeId = elements[0];
                String assigneeOption = elements[1];
                wkfSchemeIdAssigneeMapping.put(wkfSchemeId,assigneeOption);
            }
        }
    }
    
    private List<String> trimStringList(List<String> stringList)
    {
        List newStringList = new ArrayList();
        for(int i = 0;i<stringList.size();i++)
        {
            String afterTrim = stringList.get(i).trim();
            if(afterTrim.length()>0)
            {
                newStringList.add(afterTrim);
            }
        }
        return newStringList;
    }
        



    public static String getString(ApplicationProperties properties, String key) {
        try {
            String r = properties.getDefaultBackedString(key);
            return r == null || r.trim().length() == 0 ? null : r;
        } catch (Exception e) {
            return null;
        }
    }

    public static String getString(ApplicationProperties properties, String key, String defaultValue) {
        String r = getString(properties, key);
        return r == null || r.trim().length() == 0 ? defaultValue : r;
    }


}






