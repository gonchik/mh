package com.easesolutions.jira.plugins.easemailhandler.handler;


import com.atlassian.crowd.embedded.api.User;
import com.atlassian.jira.ComponentManager;
import com.atlassian.jira.bc.project.component.ProjectComponent;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.config.ConstantsManager;
import com.atlassian.jira.issue.*;
import com.atlassian.jira.issue.context.JiraContextNode;
import com.atlassian.jira.issue.customfields.CustomFieldType;
import com.atlassian.jira.issue.customfields.impl.*;
import com.atlassian.jira.issue.customfields.option.Option;
import com.atlassian.jira.issue.customfields.option.Options;
import com.atlassian.jira.issue.customfields.view.CustomFieldParamsImpl;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.SummarySystemField;
import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.fields.layout.field.FieldConfigurationScheme;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.issue.label.Label;
import com.atlassian.jira.issue.label.LabelManager;
import com.atlassian.jira.issue.operation.IssueOperations;
import com.atlassian.jira.issue.priority.Priority;
import com.atlassian.jira.issue.security.IssueSecurityLevelManager;
import com.atlassian.jira.issue.watchers.WatcherManager;
import com.atlassian.jira.permission.ProjectPermissions;
import com.atlassian.jira.plugin.assignee.AssigneeResolver;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.project.version.Version;
import com.atlassian.jira.project.version.VersionManager;
import com.atlassian.jira.security.PermissionManager;
import com.atlassian.jira.security.Permissions;
import com.atlassian.jira.security.roles.ProjectRole;
import com.atlassian.jira.security.roles.ProjectRoleManager;
import com.atlassian.jira.service.util.handler.MessageHandlerContext;
import com.atlassian.jira.service.util.handler.MessageHandlerErrorCollector;
import com.atlassian.jira.service.util.handler.MessageHandlerExecutionMonitor;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.user.UserUtils;
import com.atlassian.jira.util.ErrorCollection;
import com.atlassian.jira.util.I18nHelper;
import com.atlassian.jira.util.SimpleErrorCollection;
import com.atlassian.jira.web.FieldVisibilityManager;
import com.atlassian.jira.web.action.issue.IssueCreationHelperBean;
import com.atlassian.jira.web.bean.I18nBean;
import com.atlassian.jira.workflow.WorkflowFunctionUtils;
import com.atlassian.mail.MailUtils;
import com.easesolutions.jira.plugins.easemailhandler.util.*;
import com.opensymphony.util.TextUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.ofbiz.core.entity.GenericValue;

import javax.mail.Address;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Part;
import javax.mail.internet.InternetAddress;
import java.io.IOException;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

import static com.atlassian.jira.ComponentManager.getComponentInstanceOfType;
import static org.apache.commons.lang.StringUtils.join;

/**
 * A message handler to create a new issue from an incoming message. Note: requires public noarg constructor as this
 * class is instantiated by reflection
 */
public class CreateIssueHandler extends AbstractMessageHandler
{

    public String projectKey; // default project where new issues are created
    public String issueType; // default type for new issues



    private final static String ASSIGNEE_OPTION_REPORTER = "reporter";
    private final static String ASSIGNEE_OPTION_LEAD = "lead";
    private final static String ASSIGNEE_OPTION_EMAILBODY = "emailbody";
    private final static String ASSIGNEE_OPTION_COMPONENT = "component";
    

    public void init(Map<String, String> params, MessageHandlerErrorCollector errorCollector)
    {
        log.debug("CreateIssueHandler.init(params: " + params + ")");
        super.init(params, errorCollector);
    }

    @Override
    public boolean handleMessage(Message message, MessageHandlerContext context) throws MessagingException
    {
        log.debug("CreateIssueHandler.handleMessage");

        if (!canHandleMessage(message, context.getMonitor()))
        {
            return deleteEmail;
        }

        try
        {

            StripQuoteUtils stripQuoteUtil = new StripQuoteUtils();
            String body = MailUtils.getBody(message);
            Map<String, String> fieldMap = EaseUtil.extractFields(body);
            Set<String> allFields = fieldMap.keySet();
            //check if this is an email to ignore (possiblly just to add certificate)
            String ignoreString = fieldMap.get(EaseUtil.EASE_FIELD_IGNORE);
            if (ignoreString != null && ignoreString.trim().equalsIgnoreCase("true"))
            {
                return true;
            }

            List<String> acceptedFields = new ArrayList<String>();

            //


            // get either the sender of the message, or the default reporter
            User reporter = getReporter(message, context);

            //use default reporter
            if(reporter == null)
            {
                ApplicationUser defaultReporeter = getDefaultReporter();
                if(defaultReporeter != null)
                {
                    log.info("use default reporter: " + defaultReporeter.getUsername());
                    reporter = defaultReporeter.getDirectoryUser();
                }
            }
            // no reporter - so reject the message
            if (reporter == null)
            {
                final String error = getI18nBean().getText("The sender does not match a valid user in system. Issue cannot be created.");
                context.getMonitor().warning(error);
                context.getMonitor().messageRejected(message, error);
                return false;
            }

            Project project = null;

            Project projectInSubject = EaseUtil.findProjectObjectInString(message.getSubject());
            if (projectInSubject != null)
            {
                project = projectInSubject;
            } else
            {
                String projectFieldString = fieldMap.get(EaseUtil.EASE_FIELD_PROJECTKEY);
                if (projectFieldString != null)
                {
                    Project projectInEmailBody = getProjectManager().getProjectObjByKey(projectFieldString.toUpperCase(Locale.getDefault()));

                    //check using project name
                    if(projectInEmailBody == null)
                    {
                        projectInEmailBody = getProjectManager().getProjectObjByName(projectFieldString);
                    }

                    if (projectInEmailBody != null)
                    {
                        project = projectInEmailBody;
                        acceptedFields.add(EaseUtil.EASE_FIELD_PROJECTKEY);
                    }
                    //ease: if user specify wrong project in email body, then we will not create issue in default project to prevent creating in wrong project.
                    else
                    {
                        String error = "The project specified in email body is invalid: " + projectFieldString
                                +".\nNote: please always send in email in text format.\nPlease specify the project by " +
                                "writting a line @project=xxx in email body where xxx is the project key, or project name.";
                        context.getMonitor().warning(error);
                        context.getMonitor().messageRejected(message, error);
                        return false;
                    }
                }
            }


            //find default project for incoming email
            if(project == null)
            {
                Project defaultProject = getDefaultProject();
                if(defaultProject != null)
                {
                    log.info("use default project="+defaultProject.getKey());
                    project = defaultProject;
                }
            }

            if (project == null)
            {
                final String text = getI18nBean().getText("admin.mail.no.project.configured")+" subject="
                        +message.getSubject()+"\n.Note: pleae always send in email in text format.\nPlease specify the project by " +
                        "writting a line @project=xxx in email body where xxx is the project key, or project name.";
                context.getMonitor().warning(text);
                context.getMonitor().messageRejected(message, text);
                return false;
            }

            log.debug("Project = " + project);


            // Check that the license is valid before allowing issues to be created
            // This checks for: evaluation licenses expired, user limit licenses where limit has been exceeded
            ErrorCollection errorCollection = new SimpleErrorCollection();
            // Note: want English locale here for logging purposes
            I18nHelper i18nHelper = new I18nBean(Locale.ENGLISH);

            getIssueCreationHelperBean().validateLicense(errorCollection, i18nHelper);
            if (errorCollection.hasAnyErrors())
            {
                //context.getMonitor().warning(getI18nBean().getText("admin.mail.bad.license", errorCollection.getErrorMessages().toString()));
                context.getMonitor().warning(getI18nBean().getText("Cannot create issue due to system license problem."));
                return false;
            }

            // If user does not have create permissions, there's no point proceeding. Error out here to avoid a stack
            // trace blow up from the WorkflowManager later on.
            if (!getPermissionManager().hasPermission(Permissions.CREATE_ISSUE, project, reporter, true) && reporter.getDirectoryId() != -1)
            {
                //final String error = getI18nBean().getText("admin.mail.no.create.permission", reporter.getName());
                final String error = getI18nBean().getText("Sender does not have permission to create an issue.");
                context.getMonitor().warning(error);
                context.getMonitor().messageRejected(message, error);
                return false;
            }

            //use default issue type
            IssueType defaultIssueType = ComponentAccessor.getIssueTypeSchemeManager().getDefaultIssueType(project);
            if(defaultIssueType != null)
            {
                issueType = defaultIssueType.getId();
            }

            //ease: change the issue type to the one specified in email
            if (fieldMap.containsKey(EaseUtil.EASE_FIELD_ISSUETYPE.toLowerCase()))
            {
                String fValue = fieldMap.get(EaseUtil.EASE_FIELD_ISSUETYPE.toLowerCase());
                fValue = (fValue == null) ? "" : fValue.trim();
                if (!fValue.isEmpty())
                {
                    Collection<IssueType> availableTypes = ComponentManager.getInstance().getIssueTypeSchemeManager().
                            getIssueTypesForProject(project);
                    for (Iterator iterator = availableTypes.iterator(); iterator.hasNext(); )
                    {
                        IssueType issueTypeTmp = (IssueType) iterator.next();

                        if (issueTypeTmp.getName().equalsIgnoreCase(fValue))
                        {
                            String newId = issueTypeTmp.getId();
                            issueType = newId;
                            acceptedFields.add(EaseUtil.EASE_FIELD_ISSUETYPE);
                            break;
                        }
                    }
                }
            }


            log.debug("Issue Type Key = = " + issueType);
            if (!hasValidIssueType())
            {
                context.getMonitor().warning(getI18nBean().getText("admin.mail.invalid.issue.type")
                        +". Note: subtask is not allowed to be created via email");
                return false;
            }


            String summary = message.getSubject();
            summary = EaseUtil.stripeNewIssueSyntax(summary, project.getKey()).trim();
            if (!TextUtils.stringSet(summary))
            {
                context.getMonitor().error(getI18nBean().getText("admin.mail.no.subject"));
                return false;
            }
            if (summary.length() > SummarySystemField.MAX_LEN.intValue())
            {
                context.getMonitor().info("Truncating summary field because it is too long: " + summary);
                summary = summary.substring(0, SummarySystemField.MAX_LEN.intValue() - 3) + "...";
            }

            // JRA-7646 - check if priority/description is hidden - if so, do not set
            String priority = null;

            if (!getFieldVisibilityManager().isFieldHiddenInAllSchemes(project.getId(),
                    IssueFieldConstants.PRIORITY, Collections.singletonList(issueType)))
            {
                priority = getPriority(message);
            }




            MutableIssue issueObject = getIssueFactory().getIssue();
            issueObject.setProjectObject(project);
            issueObject.setSummary(summary);
            issueObject.setReporter(reporter);
            issueObject.setIssueTypeId(issueType);



            User assignee = null;
            String workflowSchemeId = ""+ComponentAccessor.getWorkflowSchemeManager().getWorkflowSchemeObj(project).getId();
            String assigneeOption = configUtil.getWkfSchemeIdAssigneeMapping().get(workflowSchemeId);

        	List<String> configOpts = null;
            if (assigneeOption != null) {
                configOpts = Arrays.asList(assigneeOption.split(ConfigUtil.MINOR_DELIMITER));
            }

            issueObject.setPriorityId(priority);

            // Ensure issue level security is correct
            setDefaultSecurityLevel(issueObject);

            /*
             * + FIXME -- set cf defaults @todo +
             */
            // set default custom field values
            // CustomFieldValuesHolder cfvh = new CustomFieldValuesHolder(issueType, project.getId());
            // fields.put("customFields", CustomFieldUtils.getCustomFieldValues(cfvh.getCustomFields()));
            Map<String, Object> fields = new HashMap<String, Object>();
            fields.put("issue", issueObject);
            // TODO: How is this supposed to work? There is no issue created yet; ID = null.
            // wseliga note: Ineed I think that such call does not make sense - it will be always null
            MutableIssue originalIssue = getIssueManager().getIssueObject(issueObject.getId());

            // Give the CustomFields a chance to set their default values JRA-11762
            List<CustomField> customFieldObjects = ComponentAccessor.getCustomFieldManager().getCustomFieldObjects(issueObject);
            for (CustomField customField : customFieldObjects)
            {
                issueObject.setCustomFieldValue(customField, customField.getDefaultValue(issueObject));
            }

            //ease: set the field according to email body
            //ease: set the field value according to email body
            //1. set system fields
            //2. set custom field
            //3. check mandatory fields are all set(accepted)


            //1. check mandatory fields

            //get mandatoryField configuration
            String fieldConfigurationSchemeIdString = "";
            FieldConfigurationScheme fcs = ComponentAccessor.getFieldLayoutManager().getFieldConfigurationScheme(project);
            if(fcs!=null)
            {
                fieldConfigurationSchemeIdString = ""+fcs.getId();
            }
            List<String> easeMandatoryFieldIds = configUtil.getProjectCompulsoryFields().get(fieldConfigurationSchemeIdString);
            if(easeMandatoryFieldIds == null)
            {
                easeMandatoryFieldIds = new ArrayList<String>();
            }
            List<String> easeMandatoryFields = new ArrayList<String>();

            for(String fieldId : easeMandatoryFieldIds)
            {
                String easeField = EaseUtil.convertToEaseFieldName(fieldId);
                if(easeField!=null)
                {
                    easeMandatoryFields.add(easeField);
                }
            }
            // get required field
            List<String> requiredFieldString = EaseUtil.getRequiredFieldString(issueObject);

            // get create issue screen, we only need to set the fields appear in create issue screen
            List<String> fieldStringInCreateIssueScreen = EaseUtil.getFieldStringInIssueOperationForm(issueObject
                    ,IssueOperations.CREATE_ISSUE_OPERATION);
            log.debug("fieldStringInCreateIssueScreen="+fieldStringInCreateIssueScreen);
            List<String> effectiveRequiredFieldString = new ArrayList<String>();
            String compulsoryFieldsString = "";
            if (easeMandatoryFields != null)
            {


                for (String mandatoryField : easeMandatoryFields)
                {
                    //only check those required fields appear in create issue screen and also appear in compulsory fields in mail-in config
                    //so we can turn off the required field check by setting no compulsory fields in mail-in config
                    if(requiredFieldString.contains(mandatoryField)
                            && fieldStringInCreateIssueScreen.contains(mandatoryField))
                    {
                        compulsoryFieldsString = compulsoryFieldsString + mandatoryField + " , ";
                        effectiveRequiredFieldString.add(mandatoryField);
                    }
                }
                if(!compulsoryFieldsString.isEmpty())
                {
                    compulsoryFieldsString = compulsoryFieldsString.substring(0,compulsoryFieldsString.length()-3);
                }
                for (String mandatoryField : effectiveRequiredFieldString)
                {
                    //if there is any mandatory field missing then add error and return false
                    if (!allFields.contains(mandatoryField))
                    {
                        final String error = getI18nBean().getText("The following mandatory field is missing when creating " +
                                "issue: " + mandatoryField + "\n.Note the following are mandatory " +
                                "fields: " + compulsoryFieldsString + "\n");
                        context.getMonitor().warning(error);
                        context.getMonitor().messageRejected(message, error);
                        return false;
                    }
                }
            }

            CustomFieldManager cfm = ComponentManager.getInstance().getCustomFieldManager();
            User assigneeInEmailBody = null;
            boolean assigneeAssignedFromEmail = false;
            //2. re-set jira fields
            for (String f : allFields)
            {
                //ease only check those fields appear in create issue screen
                if(!fieldStringInCreateIssueScreen.contains(f))
                {
                    log.debug("field not appear in create issue screen, ignore, field="+f);
                    continue;
                }

                String fValue = fieldMap.get(f);
                fValue = (fValue == null) ? "" : fValue.trim();
                if (fValue.isEmpty())
                {
                    continue;
                }

                if(f.equalsIgnoreCase(EaseUtil.EASE_FIELD_ASSIGNEE))
                {
                    //if sender has no assign issue permission, skip it
                    if(!getPermissionManager().hasPermission(Permissions.ASSIGN_ISSUE,project,reporter,true))
                    {
                        continue;
                    }

                    assigneeInEmailBody = ComponentAccessor.getUserManager().getUser(fValue);
                    //check permission for the specified assignee
                    if(getPermissionManager().hasPermission(Permissions.ASSIGNABLE_USER, project
                            ,assigneeInEmailBody, true) && assigneeInEmailBody.getDirectoryId() != -1)
                    {
                        assignee = assigneeInEmailBody;
                        assigneeAssignedFromEmail = true;
                        acceptedFields.add(EaseUtil.EASE_FIELD_ASSIGNEE);
                    }
                }
                else if (f.equalsIgnoreCase(EaseUtil.EASE_FIELD_ISSUETYPE))
                {
                    //already handled before issue is created
                }
                else if (f.equalsIgnoreCase(EaseUtil.EASE_FIELD_PRIORITY))
                {

                    Collection collection = ComponentManager.getInstance().getConstantsManager().getPriorityObjects();
                    for (Iterator iterator = collection.iterator(); iterator.hasNext(); )
                    {
                        Priority p = (Priority) iterator.next();
                        if ((p.getName().equalsIgnoreCase(fValue)))
                        {
                            issueObject.setPriority(p.getGenericValue());
                            acceptedFields.add(EaseUtil.EASE_FIELD_PRIORITY);
                            break;
                        }
                    }
                } else if (f.equalsIgnoreCase(EaseUtil.EASE_FIELD_COMPONENTS))
                {

                    Collection projectComponents = project.getProjectComponents();
                    String[] componentsFValues = fValue.split(",");
                    Collection<ProjectComponent> componentsList = new ArrayList<ProjectComponent>();

                    for (int i = 0; i < componentsFValues.length; i++)
                    {
                        String c = componentsFValues[i].trim();
                        for (Iterator iterator = projectComponents.iterator(); iterator.hasNext(); )
                        {
                            ProjectComponent aComponent = (ProjectComponent) iterator.next();
                            String name = aComponent.getName();
                            if (name.trim().equalsIgnoreCase(c))
                            {
                                componentsList.add(aComponent);
                                break;
                            }
                        }
                    }
                    if (componentsList.size() > 0)
                    {
                        issueObject.setComponentObjects(componentsList);
                    }
                    if (componentsList.size() == componentsFValues.length)
                    {
                        acceptedFields.add(EaseUtil.EASE_FIELD_COMPONENTS);
                    }
                } else if (f.equalsIgnoreCase(EaseUtil.EASE_FIELD_ENVIRONMENT))
                {
                    issueObject.setEnvironment(fValue);
                    acceptedFields.add(EaseUtil.EASE_FIELD_ENVIRONMENT);
                } else if (f.equalsIgnoreCase(EaseUtil.EASE_FIELD_DUEDATE))
                {
                    //check schedule issue permission
                    if(!permissionManager.hasPermission(Permissions.SCHEDULE_ISSUE,project,reporter))
                    {
                        log.warn("user has no schedule issue permission, ignored field "+f);
                        continue;
                    }

                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
                    try
                    {
                        Date d = sdf.parse(fValue);
                        Timestamp tsm = new Timestamp(d.getTime());
                        issueObject.setDueDate(tsm);
                        acceptedFields.add(EaseUtil.EASE_FIELD_DUEDATE);
                    }
                    catch (ParseException ex)
                    {

                    }

                } else if (f.equalsIgnoreCase(EaseUtil.EASE_FIELD_AFFECTEDVERSION))
                {

                    VersionManager vm = ComponentAccessor.getVersionManager();
                    List<Version> validVersion = new ArrayList<Version>();
                    String[] versionsFValues = fValue.split(",");
                    for(String v : versionsFValues)
                    {
                        Version version = vm.getVersion(project.getId(),v);
                        if(version!=null)
                        {
                            validVersion.add(version);
                        }
                    }

                    issueObject.setAffectedVersions(validVersion);
                    if(versionsFValues.length == validVersion.size())
                    {
                        acceptedFields.add(EaseUtil.EASE_FIELD_AFFECTEDVERSION);
                    }
                } else if (f.equalsIgnoreCase(EaseUtil.EASE_FIELD_FIXVERSION))
                {

                    //check if the sender has resolve issue permission
                    if(!permissionManager.hasPermission(Permissions.RESOLVE_ISSUE,project,reporter))
                    {
                        log.warn("user has no resolve issue permission, ignored field "+f);
                        continue;
                    }

                    VersionManager vm = ComponentAccessor.getVersionManager();
                    List<Version> validVersion = new ArrayList<Version>();
                    String[] versionsFValues = fValue.split(",");

                    for(String v : versionsFValues)
                    {
                        Version version = vm.getVersion(project.getId(),v);

                        if(version!=null)
                        {
                            log.debug("found version=" + version.getName());
                            validVersion.add(version);
                        }
                        else
                        {
                            log.warn("cannot find version=" + v);
                        }
                    }

                    issueObject.setFixVersions(validVersion);
                    if(versionsFValues.length == validVersion.size())
                    {
                        acceptedFields.add(EaseUtil.EASE_FIELD_FIXVERSION);
                    }
                } else
                {

                }
            }

            if (!assigneeAssignedFromEmail) {
            	assigneeOption = getValidAssignee(configOpts, issueObject, assigneeInEmailBody);
            }
            
            if(ASSIGNEE_OPTION_REPORTER.equalsIgnoreCase(assigneeOption))
            {
                assignee = reporter;
            }
            else if(ASSIGNEE_OPTION_LEAD.equalsIgnoreCase(assigneeOption))
            {
                assignee = project.getLead();
            }
            else if(ASSIGNEE_OPTION_COMPONENT.equalsIgnoreCase(assigneeOption))
            {
                assignee = getProjectManager().getDefaultAssignee(project, issueObject.getComponentObjects());
            }
            if(assignee==null) 
            {
                assignee = getProjectManager().getDefaultAssignee(project, issueObject.getComponentObjects());
            }
            if (assignee != null)
            {
                issueObject.setAssignee(assignee);
            }


            //set custom field "Sender Email"
            CustomField senderEmailCf = getSenderEmailCF();
            if(senderEmailCf!=null)
            {
                issueObject.setCustomFieldValue(senderEmailCf, reporter.getEmailAddress());
            }

            //set to field

            CustomField toCf = getToCf();
            if(toCf!=null)
            {
                String toFieldValue = getAddressesString(message.getRecipients(Message.RecipientType.TO), mailBoxEmail);
                if(!toFieldValue.isEmpty())
                {
                    issueObject.setCustomFieldValue(toCf, toFieldValue);
                }
            }

            //set cc field
            CustomField ccCf = getCcCf();
            if(ccCf!=null)
            {
                String ccFieldValue = getAddressesString(message.getRecipients(Message.RecipientType.CC), mailBoxEmail);
                if(!ccFieldValue.isEmpty())
                {
                    issueObject.setCustomFieldValue(ccCf, ccFieldValue);
                }
            }

            for (String f : allFields)
            {
                log.info("processing field "+f);
                String fValue = fieldMap.get(f);
                fValue = (fValue == null) ? "" : fValue.trim();
                if (fValue.isEmpty())
                {
                    log.info("field has no value, ignore");
                    continue;
                }

                if (!EaseUtil.isSystemFields(f))
                {
                    //3. set custom field

                    //check restricted customfields
                    boolean restricted = false;
                    boolean allowed = false;
                    Long fieldId = 0L;

                    List<RestrictedField> restrictedFields = configUtil.getProjectsRestrictedFields().get(fieldConfigurationSchemeIdString);
                    if (restrictedFields != null)
                    {
                        ProjectRoleManager prm = ComponentManager.getComponent(ProjectRoleManager.class);
                        Collection<ProjectRole> proles = prm.getProjectRoles(reporter, project);

                        for (RestrictedField rf : restrictedFields)
                        {
                            String rfName = rf.getFieldLowerName();
                            if (f.equalsIgnoreCase(rfName))
                            {
                                if (rf.getAllowReporter().equalsIgnoreCase("true"))
                                {
                                    allowed = true;
                                } else
                                {
                                    List<String> allowedProjectRoles = rf.getAllowedProjectRoles();
                                    for (String allowedPR : allowedProjectRoles)
                                    {
                                        for (ProjectRole pr : proles)
                                        {
                                            if (pr.getName().equalsIgnoreCase(allowedPR))
                                            {
                                                allowed = true;
                                                break;
                                            }
                                        }
                                        if (allowed)
                                        {
                                            break;
                                        }
                                    }
                                }

                                try
                                {
                                    fieldId = Long.parseLong(rf.getFieldId());

                                } catch (NumberFormatException ex)
                                {
                                    log.error("Invalid format when parse the id for restricted custom field " + f);
                                }


                                restricted = true;
                                break;
                            }
                        }
                    }

                    if (restricted && !allowed)
                    {
                        log.info("field is restricted, so ignore");
                        continue;
                    }

                    CustomField cf = null;

                    if (restricted)
                    {
                        if (fieldId != 0L)
                        {

                            cf = cfm.getCustomFieldObject(fieldId);
                            if (!cf.getName().equalsIgnoreCase(f))
                            {
                                log.error("Error in restricted field id setting. the id is " + fieldId + " which not match field name " + f);
                                continue;
                            }
                        } else
                        {
                            cf = IssueUtil.getCorrectCustomField(cfm, issueObject, f);
                        }
                    } else
                    {
                        cf  = IssueUtil.getCorrectCustomField(cfm, issueObject, f);
                    }


                    if (cf != null)
                    {
                        Object newValue = null;
                        CustomFieldType cfType = cf.getCustomFieldType();
                        log.info("field cftype="+cfType.getName());

                        if (cfType instanceof MultiSelectCFType)
                        {
                            log.info("haddling as MultiSelectCFType");
                            String[] newOptions = fValue.split(",");
                            List<Option> nominatedOptions = new ArrayList<Option>();
                            List schemes = cf.getConfigurationSchemes();
                            JiraContextNode jiraContextNode = (JiraContextNode) ((FieldConfigScheme) schemes.get(0)).getContexts().get(0);
                            Options allOptions = ((MultiSelectCFType) cfType).getOptions(cf.getRelevantConfig(issueObject), jiraContextNode);
                            for (String v : newOptions)
                            {
                                for (Iterator iterator = allOptions.iterator(); iterator.hasNext(); )
                                {
                                    Option option = (Option) iterator.next();
                                    if (option.getValue().equalsIgnoreCase(v.trim()))
                                    {
                                        nominatedOptions.add(option);
                                    }
                                }
                            }


                            if (nominatedOptions.size() > 0)
                            {
                                newValue = nominatedOptions;
                                issueObject.setCustomFieldValue(cf, newValue);

                                //only if all values is valid option in multi-select, we consider it is accepted, and hide the stringfrom "Description"
                                if (nominatedOptions.size() == newOptions.length)
                                {
                                    acceptedFields.add(f);
                                }
                            }
                        } else if (cfType instanceof SelectCFType)
                        {
                            log.info("haddling as SelectCFType");
                            List schemes = cf.getConfigurationSchemes();
                            JiraContextNode jiraContextNode = (JiraContextNode) ((FieldConfigScheme) schemes.get(0)).getContexts().get(0);
                            Options allOptions = ((SelectCFType) cfType).getOptions(cf.getRelevantConfig(issueObject), jiraContextNode);
                            for (Iterator iterator = allOptions.iterator(); iterator.hasNext(); )
                            {
                                Option option = (Option) iterator.next();
                                if (option.getValue().equalsIgnoreCase(fValue))
                                {
                                    newValue = option;
                                    issueObject.setCustomFieldValue(cf, newValue);
                                    acceptedFields.add(f);
                                }
                            }
                        } else if (cfType instanceof TextCFType || cfType instanceof GenericTextCFType)
                        {
                            log.info("haddling as TextCFType or GenericTextCFType");
                            newValue = cfType.getSingularObjectFromString(fValue);
                            issueObject.setCustomFieldValue(cf, newValue);
                            acceptedFields.add(f);
                        } else if (cfType instanceof NumberCFType)
                        {
                            log.info("haddling as NumberCFType");
                            newValue = cfType.getSingularObjectFromString(fValue);
                            issueObject.setCustomFieldValue(cf, newValue);
                            acceptedFields.add(f);
                        } else if (cfType instanceof MultiUserCFType)
                        {
                            log.info("haddling as MultiUserCFType");
                            String[] list = fValue.split(",");
                            List<String> usersStringList = Arrays.asList(list);
                            List<String> validUsersStringList = new ArrayList<String>();
                            for (String userstring : usersStringList)
                            {
                                User u = messageUserProcessor.findUserByUsername(userstring.trim());
                                if (u != null)
                                {
                                    validUsersStringList.add(userstring);
                                } else
                                {
                                    log.warn("Found invalid username when process MultiUserCFType, username=" + userstring);
                                }
                            }
                            CustomFieldParamsImpl a = new CustomFieldParamsImpl(cf, validUsersStringList);
                            newValue = cfType.getValueFromCustomFieldParams(a);
                            issueObject.setCustomFieldValue(cf, newValue);
                            if (list.length == validUsersStringList.size())
                            {
                                acceptedFields.add(f);
                            }
                        } else if (cfType instanceof LabelsCFType)
                        {
                            log.info("haddling as LabelsCFType");
                            Set<Label> defaultLabels = new LinkedHashSet<Label>();
                            String[] labelsString = fValue.split(",");
                            for (int i = 0; i < labelsString.length; i++)
                            {
                                String v = labelsString[i].trim();
                                if (v.length() > 0)
                                {
                                    defaultLabels.add((Label) cfType.getSingularObjectFromString(labelsString[i].trim()));
                                }
                            }
                            if (defaultLabels.size() > 0)
                            {
                                issueObject.setCustomFieldValue(cf, defaultLabels);
                                acceptedFields.add(f);
                            }
                        } else
                        {
                            continue;
                        }
                    }
                }
            }

            //ease: we assume description is never hidden, so checking visibility is not needed
            //ease: set description here, as we have to remove @xxx=xxx if @xxx is accepted
            String originalDescription = getDescription(reporter, message, body);
            //ease: we assume labels field always been processed correctly as it is free text
            boolean isLabelsFieldVisible = !getFieldVisibilityManager().isFieldHidden(IssueFieldConstants.LABELS, issueObject)
                    && fieldStringInCreateIssueScreen.contains(EaseUtil.EASE_FIELD_LABELS);
            if(isLabelsFieldVisible)
            {
                acceptedFields.add(EaseUtil.EASE_FIELD_LABELS);
            }
            String afterRemoveAcceptedField = EaseUtil.removeAcceptedFields(originalDescription, acceptedFields);
            if (!getFieldVisibilityManager().isFieldHiddenInAllSchemes(project.getId(), IssueFieldConstants.DESCRIPTION, Collections.singletonList(issueType)))
            {
                issueObject.setDescription(afterRemoveAcceptedField);
            }

            fields.put(WorkflowFunctionUtils.ORIGINAL_ISSUE_KEY, originalIssue);

            //check if acceptedFields contains all mandatory fields
            for (String mandatoryField : effectiveRequiredFieldString)
            {
                //if there is any mandatory field missing then add error and return false
                if (!acceptedFields.contains(mandatoryField))
                {
                    final String error = getI18nBean().getText("The mandatory field value is invalid when creating " +
                            "issue: " + mandatoryField + "\n.Note the following are mandatory " +
                            "fields: " + compulsoryFieldsString + "\n");
                    context.getMonitor().warning(error);
                    context.getMonitor().messageRejected(message, error);
                    return false;
                }
            }

            final Issue issue = context.createIssue(reporter, issueObject);


            //ease: set label, since it requires Issue ID, so set it here, and make sure the field is not hidden.
            if (issueObject.getId() != null && isLabelsFieldVisible)
            {
                String fValue = fieldMap.get(EaseUtil.EASE_FIELD_LABELS);
                if (fValue != null && !fValue.trim().isEmpty())
                {
                    LabelManager lm = ComponentManager.getComponent(LabelManager.class);
                    fValue = fValue.trim();
                    Set<String> labelStringSet = new LinkedHashSet<String>();
                    String[] labelsString = fValue.split(",");
                    for (int i = 0; i < labelsString.length; i++)
                    {
                        String v = labelsString[i].trim();
                        if (v.length() > 0)
                        {
                            labelStringSet.add(v);
                        }
                    }
                    lm.setLabels(reporter, issue.getId(), labelStringSet, false, false);
                }
            }


            if (issue != null)
            {
                //ease: we do not need this
                // Record the message id of this e-mail message so we can track replies to this message from CC-ed
                // recipients and associate them with this issue even if there is no Issue Key in the subject.
                //recordMessageId(MailThreadManager.ISSUE_CREATED_FROM_EMAIL, message, issue.getId(), context);
            }

            // TODO: if this throws an error, then the issue is already created
            createAttachmentsForMessage(message, issue, context);

            return true;
        } catch (Exception e)
        {
            log.error(e.getMessage(),e);
            e.printStackTrace();
            String errorMsg = getI18nBean().getText("admin.mail.unable.to.create.issue")+".\n";
            String[] rootCauses = ExceptionUtils.getRootCauseStackTrace(e);
            if(rootCauses!=null)
            {

                errorMsg+="RootCause: "+rootCauses[0];

            }
            else
            {
                errorMsg = errorMsg + ExceptionUtils.getStackTrace(e);
            }

            context.getMonitor().warning(getI18nBean().getText(errorMsg, e));
            //return the full stack trace to the alert email
            //context.getMonitor().messageRejected(message, ExceptionUtils.getStackTrace(e));
        }
        //todo something went wrong - do not delete the message
        return false;
    }

    private String getValidAssignee(List<String> assigneeList, MutableIssue issueObject, User assigneInEmailBody) {
        if(assigneeList == null)
        {
            return "";
        }
    	for (String assigneeOption : assigneeList) {
			if ((assigneeOption.equals(ASSIGNEE_OPTION_REPORTER)) || (assigneeOption.equals(ASSIGNEE_OPTION_LEAD))) {
				return assigneeOption;
			}
			if (assigneeOption.equals(ASSIGNEE_OPTION_COMPONENT)) {
				if (issueObject.getComponentObjects().size() > 0) {
					return assigneeOption;
				}
			}
			if (assigneeOption.equals(ASSIGNEE_OPTION_EMAILBODY)) {
				if (assigneInEmailBody != null) {
					return assigneeOption;
				}
			}
		}
    	return "";
    }
    
    private IssueCreationHelperBean getIssueCreationHelperBean()
    {
        return ComponentAccessor.getComponent(IssueCreationHelperBean.class);
    }

    PermissionManager getPermissionManager()
    {
        return ComponentAccessor.getPermissionManager();
    }

    FieldVisibilityManager getFieldVisibilityManager()
    {
        return ComponentAccessor.getComponent(FieldVisibilityManager.class);
    }

    AssigneeResolver getAssigneeResolver()
    {
        return ComponentAccessor.getComponent(AssigneeResolver.class);
    }

    IssueManager getIssueManager()
    {
        return ComponentAccessor.getIssueManager();
    }

    IssueFactory getIssueFactory()
    {
        return ComponentAccessor.getIssueFactory();
    }

    /**
     * Adds all valid users that are in the email to and cc fields as watchers of the issue.
     *
     * @param message                        message to extract the email addresses from
     * @param issue                          issue to add the watchers to
     * @param reporter
     * @param context
     * @param messageHandlerExecutionMonitor @throws MessagingException message errors
     */
    public void addCcWatchersToIssue(Message message, Issue issue, User reporter, MessageHandlerContext context, MessageHandlerExecutionMonitor messageHandlerExecutionMonitor) throws MessagingException
    {
        Collection<User> users = getAllUsersFromEmails(message.getAllRecipients());
        //we don't want to add the reporter to the watchers list, lets get rid of him.
        users.remove(reporter);
        if (!users.isEmpty())
        {
            if (context.isRealRun())
            {
                for (User user : users)
                {
                    getWatcherManager().startWatching(user, issue);
                }
            } else
            {
                final String watchers = join(users, ",");
                messageHandlerExecutionMonitor.info("Adding watchers " + watchers);
                log.debug("Watchers [" + watchers + "] not added due to dry-run mode.");
            }
        }
    }

    WatcherManager getWatcherManager()
    {
        return getComponentInstanceOfType(WatcherManager.class);
    }

    public Collection<User> getAllUsersFromEmails(Address addresses[])
    {
        if (addresses == null || addresses.length == 0)
        {
            return Collections.emptyList();
        }
        final List<User> users = new ArrayList<User>();
        for (Address address : addresses)
        {
            String emailAddress = getEmailAddress(address);
            if (emailAddress != null)
            {
                User user = UserUtils.getUserByEmail(emailAddress);
                if (user != null)
                {
                    users.add(user);
                }
            }
        }
        return users;
    }

    private String getEmailAddress(Address address)
    {
        if (address instanceof InternetAddress)
        {
            InternetAddress internetAddress = (InternetAddress) address;
            return internetAddress.getAddress();
        }
        return null;
    }

    protected Project getProject(Message message)
    {
        // if there is no project then the issue cannot be created
        if (projectKey == null)
        {
            log.debug("Project key NOT set. Cannot find project.");
            return null;
        }

        log.debug("Project key = " + projectKey);

        return getProjectManager().getProjectObjByKey(projectKey.toUpperCase(Locale.getDefault()));
    }

    protected boolean hasValidIssueType()
    {
        // if there is no project then the issue cannot be created
        if (issueType == null)
        {
            log.debug("Issue Type NOT set. Cannot find Issue type.");
            return false;
        }

        IssueType issueTypeObject = getConstantsManager().getIssueTypeObject(issueType);
        if (issueTypeObject == null)
        {
            log.debug("Issue Type with does not exist with id of " + issueType);
            return false;
        }
        //subtask issue type should not be created via email
        if(issueTypeObject.isSubTask())
        {
            log.error("Issue type "+issueTypeObject.getName()+" is subtask, not allowed to be created");
            return false;
        }
        log.debug("Issue Type Object = " + issueTypeObject.getName());
        return true;
    }

    protected ProjectManager getProjectManager()
    {
        return ComponentAccessor.getProjectManager();
    }

    /**
     * Extracts the description of the issue from the message
     *
     * @param reporter the established reporter of the issue
     * @param message  the message from which the issue is created
     * @return the description of the issue
     * @throws MessagingException if cannot find out who is the message from
     */
    private String getDescription(User reporter, Message message, String body) throws MessagingException
    {
        return recordFromAddressForAnon(reporter, message, body);
    }

    /**
     * Adds the senders' from addresses to the end of the issue's details (if they could be extracted), if the e-mail
     * has been received from an unknown e-mail address and the mapping to an "anonymous" user has been enabled.
     *
     * @param reporter    the established reporter of the issue (after one has been established)
     * @param message     the message that is used to create issue
     * @param description the issues exracted description
     * @return the modified description if the e-mail is from anonymous user, unmodified description otherwise
     * @throws MessagingException if cannot find out who is the message from
     */
    private String recordFromAddressForAnon(User reporter, Message message, String description) throws MessagingException
    {
        // If the message has been created for an anonymous user add the senders e-mail address to the description.
        if (reporteruserName != null && reporteruserName.equals(reporter.getName()))
        {
            description += "\n[Created via e-mail ";
            if (message.getFrom() != null && message.getFrom().length > 0)
            {
                description += "received from: " + message.getFrom()[0] + "]";
            } else
            {
                description += "but could not establish sender's address.]";
            }
        }
        return description;
    }

    /**
     * Using the first 'X-Priority' from the message, get the issue's priority
     *
     * @param message message
     * @return message priority
     * @throws MessagingException if cannot read message's header
     */
    private String getPriority(Message message) throws MessagingException
    {
        String[] xPrioHeaders = message.getHeader("X-Priority");

        if (xPrioHeaders != null && xPrioHeaders.length > 0)
        {
            String xPrioHeader = xPrioHeaders[0];

            int priorityValue = Integer.parseInt(TextUtils.extractNumber(xPrioHeader));

            if (priorityValue == 0)
            {
                return getDefaultSystemPriority();
            }

            // if priority is unset - pick the closest priority, this should be a sensible default
            Collection<Priority> priorities = getConstantsManager().getPriorityObjects();

            Iterator<Priority> priorityIt = priorities.iterator();

            /*
             * NOTE: Valid values for X-priority are (1=Highest, 2=High, 3=Normal, 4=Low & 5=Lowest) The X-Priority
             * (priority in email header) is divided by 5 (number of valid values) this gives the percentage
             * representation of the priority. We multiply this by the priority.size() (number of priorities in jira) to
             * scale and map the percentage to a priority in jira.
             */
            int priorityNumber = (int) Math.ceil(((double) priorityValue / 5d) * (double) priorities.size());
            // if priority is too large, assume its the 'lowest'
            if (priorityNumber > priorities.size())
            {
                priorityNumber = priorities.size();
            }

            String priority = null;

            for (int i = 0; i < priorityNumber; i++)
            {
                priority = priorityIt.next().getId();
            }

            return priority;
        } else
        {
            return getDefaultSystemPriority();
        }
    }

    ConstantsManager getConstantsManager()
    {
        return getComponentInstanceOfType(ConstantsManager.class);
    }

    /**
     * Returns a default system priority. If default system priority if not
     * set, tries to find 'middle' priority based on other priorities. It may
     * throw RuntimeException if there is not default priority set and there
     * are no other priorities (which is highly unlikely).
     *
     * @return a default system priority
     * @throws RuntimeException if no default set and no other priorities found.
     */
    private String getDefaultSystemPriority()
    {
        // if priority header is not set, assume it's 'default'
        Priority defaultPriority = getConstantsManager().getDefaultPriorityObject();
        if (defaultPriority == null)
        {
            log.warn("Default priority was null. Using the 'middle' priority.");
            Collection<Priority> priorities = getConstantsManager().getPriorityObjects();
            final int times = (int) Math.ceil((double) priorities.size() / 2d);
            Iterator<Priority> priorityIt = priorities.iterator();
            for (int i = 0; i < times; i++)
            {
                defaultPriority = priorityIt.next();
            }
        }
        if (defaultPriority == null)
        {
            throw new RuntimeException("Default priority not found");
        }
        return defaultPriority.getId();
    }

    /**
     * Given an array of addresses, this method returns the first valid
     * assignee for the appropriate project.
     * It returns null if addresses is null or empty array, or none of the
     * users found by addresses is assignable.
     *
     * @param addresses array of addresses
     * @param project   project generic value
     * @return first assignable user based on the array of addresses
     */
    public static User getFirstValidAssignee(Address[] addresses, Project project)
    {
        if (addresses == null || addresses.length == 0)
        {
            return null;
        }

        for (Address address : addresses)
        {
            if (address instanceof InternetAddress)
            {
                InternetAddress email = (InternetAddress) address;

                User validUser = UserUtils.getUserByEmail(email.getAddress());
                if (validUser != null && getComponentInstanceOfType(PermissionManager.class).
                        hasPermission(Permissions.ASSIGNABLE_USER, project, validUser))
                {
                    return validUser;
                }
            }
        }

        return null;
    }

    private void setDefaultSecurityLevel(MutableIssue issue) throws Exception
    {
        GenericValue project = issue.getProject();
        if (project != null)
        {
            final Long levelId = getIssueSecurityLevelManager().getSchemeDefaultSecurityLevel(project);
            if (levelId != null)
            {
                issue.setSecurityLevel(getIssueSecurityLevelManager().getIssueSecurity(levelId));
            }
        }
    }

    IssueSecurityLevelManager getIssueSecurityLevelManager()
    {
        return getComponentInstanceOfType(IssueSecurityLevelManager.class);
    }

    /**
     * Text parts are not attached but rather potentially form the source of issue text.
     * However text part attachments are kept providing they aint empty.
     *
     * @param part The part which will have a content type of text/plain to be tested.
     * @return Only returns true if the part is an attachment and not empty
     * @throws MessagingException
     * @throws IOException
     */
    protected boolean attachPlainTextParts(final Part part) throws MessagingException, IOException
    {
        return !MailUtils.isContentEmpty(part) && MailUtils.isPartAttachment(part);
    }

    /**
     * Html parts are not attached but rather potentially form the source of issue text.
     * However html part attachments are kept providing they aint empty.
     *
     * @param part The part which will have a content type of text/html to be tested.
     * @return Only returns true if the part is an attachment and not empty
     * @throws MessagingException
     * @throws IOException
     */
    protected boolean attachHtmlParts(final Part part) throws MessagingException, IOException
    {
        return !MailUtils.isContentEmpty(part) && MailUtils.isPartAttachment(part);
    }

    @Override
    protected User getReporter(final Message message, MessageHandlerContext context) throws MessagingException
    {

        final List<String> senders = MailUtils.getSenders(message);
        User reporter = null;
        for (final String emailAddress : senders)
        {
            reporter = getMessageUserProcessor().findUserByEmail(emailAddress.trim());
            if (reporter != null)
            {
                break;
            }
            reporter = getMessageUserProcessor().findUserByUsername(emailAddress.trim());
            if (reporter != null)
            {
                break;
            }
        }


        if (reporter == null)
        {
            //if createUsers is set, attempt to create a new reporter from the e-mail details
            if (createUsers)
            {
                reporter = createUserForReporter(message, context);
            }

            // If there's a default reporter set, and we haven't created a reporter yet, attempt to use the
            //default reporter.
            if ((reporteruserName != null) && (reporter == null))
            {
                // Sender not registered with JIRA, use default reporter
                log.info("Sender(s) " + MailUtils.getSenders(message) + " not registered in JIRA. Using configured default reporter '" + reporteruserName + "'.");
                reporter = UserUtils.getUser(reporteruserName);
            }
        }
        return reporter;
    }

    private ApplicationUser getDefaultReporter()
    {
        String defaultReporter = ConfigUtil.getString(applicationProperties,ConfigUtil.KEY_DEFAULT_REPORTER,"");
        if(!defaultReporter.isEmpty())
        {
            ApplicationUser u =  userManager.getUserByName(defaultReporter);
            if(u==null)
            {
                log.error("default reporter is invalid:"+defaultReporter);
            }
            return u;
        }
        return null;
    }
    private Project getDefaultProject()
    {
        String defaultProject = ConfigUtil.getString(applicationProperties,ConfigUtil.KEY_DEFAULT_PROJECT,"");
        if(!defaultProject.isEmpty())
        {
            Project p =  getProjectManager().getProjectByCurrentKey(defaultProject);
            if(p==null)
            {
                log.error("default project is invalid:"+defaultProject);
            }
            return p;
        }
        return null;
    }



    private CustomField getSenderEmailCF()
    {
        String senderEmailCfId = ConfigUtil.getString(applicationProperties,ConfigUtil.KEY_SENDER_EMAIL_CF,"");
        if(!senderEmailCfId.isEmpty())
        {
            CustomField cf = ComponentAccessor.getCustomFieldManager().getCustomFieldObject(senderEmailCfId);
            if(cf == null)
            {
                log.error("invalid sender email cf id:"+senderEmailCfId);
            }
            return cf;
        }
        return null;
    }
}
