package com.easesolutions.jira.plugins.easemailhandler.util;


import com.atlassian.crowd.embedded.api.User;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.IssueConstant;
import com.atlassian.jira.issue.IssueConstantImpl;
import com.atlassian.jira.issue.IssueFieldConstants;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.IssueConstantsField;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutItem;
import com.atlassian.jira.issue.fields.screen.*;
import com.atlassian.jira.issue.operation.IssueOperation;
import com.atlassian.jira.issue.operation.IssueOperations;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.service.util.ServiceUtils;
import com.atlassian.jira.util.JiraKeyUtils;
import com.atlassian.mail.MailUtils;
import com.opensymphony.util.TextUtils;
import org.apache.commons.lang.StringUtils;

import javax.annotation.Nullable;
import javax.mail.Message;
import javax.mail.MessagingException;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EaseUtil {

    //all these must be lower case
    //do not modify these, because these are in documentations.
    public static final String EASE_FIELD_LEADING_CHAR = "@";
    public static final String EASE_FIELD_PROJECTKEY = "project";
    public static final String EASE_FIELD_SUMMARY = "summary";
    public static final String EASE_FIELD_DESCRIPTION = "description";
    public static final String EASE_FIELD_ISSUETYPE = "issuetype";
    public static final String EASE_FIELD_ASSIGNEE = "assignee";
    public static final String EASE_FIELD_PRIORITY = "priority";
    public static final String EASE_FIELD_LABELS = "labels";
    public static final String EASE_FIELD_DUEDATE = "duedate";
    public static final String EASE_FIELD_AFFECTEDVERSION = "affectedversion";
    public static final String EASE_FIELD_FIXVERSION = "fixversion";
    public static final String EASE_FIELD_ENVIRONMENT = "environment";
    public static final String EASE_FIELD_COMPONENTS = "components";
    public static final String EASE_FIELD_IGNORE = "ignoreme";
    public static final String EASE_FIELD_ISSUEKEY = "issuekey";
    public static String NEW_ISSUE_MARK = "-NEW";


    private static final String INVALID_ISSUEKEY_CHARS = "\n\r\t \"''`~,.:;<>()[]{}!@#$%^&*+=|\\/?";



    public static String removeAcceptedFields(String orig, List<String> acceptedFields)
    {
        if(orig==null)
        {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        StringTokenizer st = new StringTokenizer(orig, "\n", false);
        while (st.hasMoreTokens())
        {
            String line = st.nextToken();
            String copyLine = line.trim().toLowerCase();
            boolean match = false;
            for(String acceptedF : acceptedFields)
            {
                Pattern p = Pattern.compile("^(\\s)*"+EASE_FIELD_LEADING_CHAR+"(\\s)*"+forRegex(acceptedF)+"(\\s)*=");
                Matcher m = p.matcher(copyLine);
                if(m.find())
                {
                    match = true;
                    break;
                }
            }

            if(!match)
            {
                sb.append(line+"\n");
            }
        }
        return sb.toString();
    }

    public static String forRegex(String aRegexFragment){
        final StringBuilder result = new StringBuilder();

        final StringCharacterIterator iterator =
                new StringCharacterIterator(aRegexFragment)
                ;
        char character =  iterator.current();
        while (character != CharacterIterator.DONE ){
            /*
             All literals need to have backslashes doubled.
            */
            if (character == '.') {
                result.append("\\.");
            }
            else if (character == '\\') {
                result.append("\\\\");
            }
            else if (character == '?') {
                result.append("\\?");
            }
            else if (character == '*') {
                result.append("\\*");
            }
            else if (character == '+') {
                result.append("\\+");
            }
            else if (character == '&') {
                result.append("\\&");
            }
            else if (character == ':') {
                result.append("\\:");
            }
            else if (character == '{') {
                result.append("\\{");
            }
            else if (character == '}') {
                result.append("\\}");
            }
            else if (character == '[') {
                result.append("\\[");
            }
            else if (character == ']') {
                result.append("\\]");
            }
            else if (character == '(') {
                result.append("\\(");
            }
            else if (character == ')') {
                result.append("\\)");
            }
            else if (character == '^') {
                result.append("\\^");
            }
            else if (character == '$') {
                result.append("\\$");
            }
            else {
                //the char is not a special one
                //add it to the result as is
                result.append(character);
            }
            character = iterator.next();
        }
        return result.toString();
    }

    /**
     * Get a map of fields value extracted from message body by checking pattern @XX=XXX
     * Note that the quote will not be included during the extraction
     * @param body
     * @return
    */
    public static Map<String,String> extractFields(String body)
    {

        StripQuoteUtils stripQuoteUtil = new StripQuoteUtils();
        String nonQuotedBody = stripQuoteUtil.stripQuotedLines(body);

        Map<String,String> result = new HashMap<String, String>();
        if(nonQuotedBody==null)
        {
            return result;
        }
        StringTokenizer st = new StringTokenizer(nonQuotedBody, "\n", false);
        while (st.hasMoreTokens())
        {
            String line = st.nextToken().trim();
            if(line.startsWith(EASE_FIELD_LEADING_CHAR))
            {
                int equalIndex = line.indexOf('=');
                if(equalIndex!=-1)
                {
                    //@abc  = value will get "abc" as field; @ abc = value will get " abc" as value, note for field we only strip the trailing empty space,
                    //because "@" and field name should be directly connected without space.
                    String fieldName = StringUtils.stripEnd(line.substring(1, equalIndex), null).toLowerCase();//line.substring(1,equalIndex)..toLowerCase();
                    String fieldValue = line.substring(equalIndex+1, line.length()).trim();

                    //if field value is length 0, still put in the map, and it means remove the custom field value
                    if(fieldName.length()>0)
                    {
                        result.put(fieldName,fieldValue);
                    }
                }
            }
        }

        return result;
    }
    /**
     * Find the first project in subject with pattern   [projectkey-new], {projectkey-new}, (projectkey-new), <projectkey-new>
     * @param searchString
     * @return
     */
     @Nullable
    public static Project findProjectObjectInString(final String searchString)
    {
        if(searchString==null || searchString.trim().isEmpty())
        {
            return null;
        }

        String p1 = String.format("\\[([A-Z0-9]+)%s\\]", NEW_ISSUE_MARK);
        String p2 = String.format("\\(([A-Z0-9]+)%s\\)", NEW_ISSUE_MARK);
        String p3 = String.format("\\{([A-Z0-9]+)%s\\}", NEW_ISSUE_MARK);
        String p4 = String.format("<([A-Z0-9]+)%s>", NEW_ISSUE_MARK);
        String[] pList = {p1,p2,p3,p4};
        ProjectManager pm = ComponentAccessor.getProjectManager();
        for(String p : pList)
        {
            Pattern pattern = Pattern.compile(p);
            Matcher r = pattern.matcher(searchString.toUpperCase(Locale.getDefault()));
            while (r.find())
            {
                String projectKey = r.group(r.groupCount());
                Project projectObj = pm.getProjectObjByKey(projectKey);
                if(projectObj!=null)
                {
                    return projectObj;
                }
            }
        }

        final StringTokenizer tokenizer = new StringTokenizer(TextUtils.noNull(searchString)
                .toUpperCase(Locale.getDefault()), " \n\r\t", false);
        String token;
        while (tokenizer.hasMoreTokens())
        {
            token = tokenizer.nextToken();
            int i = token.indexOf(NEW_ISSUE_MARK);
            if(i!=-1)
            {
                String projKey = token.substring(0,i);
                Project projectObj = pm.getProjectObjByKey(projKey.toUpperCase(Locale.getDefault()));
                if(projectObj!=null)
                {
                    return projectObj;
                }
            }
        }

        return null;
    }

    public static String stripeNewIssueSyntax(String input, String projectKey)
    {
        String output=input.replaceAll(String.format("(?i)\\[%s%s\\]", projectKey, NEW_ISSUE_MARK), " ");
        output=output.replaceAll(String.format("(?i)\\{%s%s\\}",projectKey,NEW_ISSUE_MARK)," ");
        output=output.replaceAll(String.format("(?i)\\(%s%s\\)",projectKey,NEW_ISSUE_MARK)," ");
        output=output.replaceAll(String.format("(?i)<%s%s>",projectKey,NEW_ISSUE_MARK)," ");
        output=output.replaceAll(String.format("(?i)[ \\n\\r\\t]%s%s[ \\n\\r\\t]",projectKey,NEW_ISSUE_MARK)," ");
        output=output.replaceAll(String.format("(?i)^%s%s[ \\n\\r\\t]",projectKey,NEW_ISSUE_MARK)," ");
        output=output.replaceAll(String.format("(?i)[ \\n\\r\\t]%s%s$",projectKey,NEW_ISSUE_MARK)," ");
        output=output.replaceAll(String.format("(?i)^%s%s$",projectKey,NEW_ISSUE_MARK)," ");
        return output;
    }

     @Nullable
    public static Issue findIssueObjectInString(final String searchString)
    {
        if(searchString==null || searchString.trim().isEmpty())
        {
            return null;
        }


        String p1 = String.format("\\[([A-Z0-9]+-[0-9]+)\\]");
        String p2 = String.format("\\(([A-Z0-9]+-[0-9]+)\\)");
        String p3 = String.format("\\{([A-Z0-9]+-[0-9]+)\\}");
        String p4 = String.format("<([A-Z0-9]+-[0-9]+)>");
        String[] pList = {p1,p2,p3,p4};

        for(String p : pList)
        {
            Pattern pattern = Pattern.compile(p);
            Matcher r = pattern.matcher(searchString.toUpperCase(Locale.getDefault()));
            while (r.find())
            {
                String issueKey = r.group(r.groupCount());

                final Issue issue = ServiceUtils.getIssueObject(issueKey);
                if (issue != null)
                {
                    return issue;
                }
            }
        }


        final StringTokenizer tokenizer = new StringTokenizer(TextUtils.noNull(searchString)
                .toUpperCase(Locale.getDefault()), INVALID_ISSUEKEY_CHARS, false);
        String token;
        while (tokenizer.hasMoreTokens())
        {
            token = tokenizer.nextToken();

            if (JiraKeyUtils.validIssueKey(token))
            {
                final Issue issue = ServiceUtils.getIssueObject(token);
                if (issue != null)
                {
                    return issue;
                }
            }
        }
        return null;
    }


    /**
     * return null if there is no match
     * @param fieldId
     * @return
     */
    public static String convertToEaseFieldName(String fieldId)
    {
        if(fieldId.equalsIgnoreCase(IssueFieldConstants.PROJECT))
        {
            return EASE_FIELD_PROJECTKEY;
        }
        else if(fieldId.equalsIgnoreCase(IssueFieldConstants.SUMMARY)){
            return EASE_FIELD_SUMMARY;
        }
        else if(fieldId.equalsIgnoreCase(IssueFieldConstants.DESCRIPTION)){
            return EASE_FIELD_DESCRIPTION;
        }
        else if(fieldId.equalsIgnoreCase(IssueFieldConstants.ISSUE_TYPE)){
            return EASE_FIELD_ISSUETYPE;
        }
        else if(fieldId.equalsIgnoreCase(IssueFieldConstants.ASSIGNEE)){
            return EASE_FIELD_ASSIGNEE;
        }
        else if(fieldId.equalsIgnoreCase(IssueFieldConstants.PRIORITY)){
            return EASE_FIELD_PRIORITY;
        }
        else if(fieldId.equalsIgnoreCase(IssueFieldConstants.LABELS)){
            return EASE_FIELD_LABELS;
        }
        else if(fieldId.equalsIgnoreCase(IssueFieldConstants.DUE_DATE)){
            return EASE_FIELD_DUEDATE;
        }
        else if(fieldId.equalsIgnoreCase(IssueFieldConstants.AFFECTED_VERSIONS)){
            return EASE_FIELD_AFFECTEDVERSION;
        }
        else if(fieldId.equalsIgnoreCase(IssueFieldConstants.FIX_FOR_VERSIONS)){
            return EASE_FIELD_FIXVERSION;
        }
        else if(fieldId.equalsIgnoreCase(IssueFieldConstants.ENVIRONMENT)){
            return EASE_FIELD_ENVIRONMENT;
        }
        else if(fieldId.equalsIgnoreCase(IssueFieldConstants.COMPONENTS)){
            return EASE_FIELD_COMPONENTS;
        }
        else if(fieldId.equalsIgnoreCase(IssueFieldConstants.ISSUE_KEY)){
            return EASE_FIELD_ISSUEKEY;
        }
        //assume it is custom field
        else
        {
            CustomField cf = ComponentAccessor.getCustomFieldManager().getCustomFieldObject(fieldId);
            if(cf!=null)
            {
                return cf.getName().toLowerCase();
            }
            return null;
        }
    }

    public static List<String> getRequiredFieldString(Issue issue)
    {
        List<FieldLayoutItem> requiredFields = ComponentAccessor.getFieldLayoutManager().getFieldLayout(issue)
                .getRequiredFieldLayoutItems(issue.getProjectObject(), Arrays.asList(issue.getIssueTypeId()));
        List<String> requiredFieldString = new ArrayList<String>();
        for(FieldLayoutItem fli : requiredFields)
        {
            String easeName = EaseUtil.convertToEaseFieldName(fli.getOrderableField().getId());
            if(easeName != null)
            {
                requiredFieldString.add(easeName);
            }
        }
        return requiredFieldString;
    }


    public static List<String> getFieldStringInIssueOperationForm(Issue issue, IssueOperation issueOperation)
    {

        List<String> fieldStringInScreen = new ArrayList<String>();


        FieldScreenScheme fieldScreenScheme = ComponentAccessor.getIssueTypeScreenSchemeManager().getFieldScreenScheme(issue);
        FieldScreen fieldScreen = fieldScreenScheme.getFieldScreen(issueOperation);
        for(FieldScreenTab tab :fieldScreen.getTabs())
        {
            List<FieldScreenLayoutItem> items = tab.getFieldScreenLayoutItems();
            for(FieldScreenLayoutItem item : items)
            {
                if(item.getOrderableField()!=null)
                {
                    String easeName = EaseUtil.convertToEaseFieldName(item.getOrderableField().getId());
                    if (easeName != null)
                    {
                        fieldStringInScreen.add(easeName);
                    }
                }
            }
        }
        return fieldStringInScreen;
    }

    public static boolean isSystemFields(String f)
    {
        return f.equalsIgnoreCase(EaseUtil.EASE_FIELD_AFFECTEDVERSION) ||
                f.equalsIgnoreCase(EaseUtil.EASE_FIELD_COMPONENTS) ||
                f.equalsIgnoreCase(EaseUtil.EASE_FIELD_DESCRIPTION) ||
                f.equalsIgnoreCase(EaseUtil.EASE_FIELD_DUEDATE) ||
                f.equalsIgnoreCase(EaseUtil.EASE_FIELD_ENVIRONMENT) ||
                f.equalsIgnoreCase(EaseUtil.EASE_FIELD_FIXVERSION) ||
                f.equalsIgnoreCase(EaseUtil.EASE_FIELD_ISSUETYPE) ||
                f.equalsIgnoreCase(EaseUtil.EASE_FIELD_PRIORITY) ||
                f.equalsIgnoreCase(EaseUtil.EASE_FIELD_PROJECTKEY) ||
                f.equalsIgnoreCase(EaseUtil.EASE_FIELD_SUMMARY) ||
                f.equalsIgnoreCase(EaseUtil.EASE_FIELD_LABELS) ||
                f.equalsIgnoreCase(EaseUtil.EASE_FIELD_ASSIGNEE) ||
                f.equalsIgnoreCase(EaseUtil.EASE_FIELD_ISSUEKEY);
    }

}
