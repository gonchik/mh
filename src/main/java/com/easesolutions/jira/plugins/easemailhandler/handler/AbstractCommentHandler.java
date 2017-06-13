package com.easesolutions.jira.plugins.easemailhandler.handler;


import com.atlassian.annotations.ExperimentalApi;
import com.atlassian.core.util.map.EasyMap;
import com.atlassian.crowd.embedded.api.User;
import com.atlassian.jira.ComponentManager;
import com.atlassian.jira.JiraApplicationContext;
import com.atlassian.jira.JiraException;
import com.atlassian.jira.bc.project.component.ProjectComponent;
import com.atlassian.jira.bc.project.component.ProjectComponentManager;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.config.properties.ApplicationProperties;
import com.atlassian.jira.event.issue.IssueEventSource;
import com.atlassian.jira.event.type.EventDispatchOption;
import com.atlassian.jira.event.type.EventType;
import com.atlassian.jira.exception.PermissionException;
import com.atlassian.jira.issue.*;
import com.atlassian.jira.issue.comments.Comment;
import com.atlassian.jira.issue.context.JiraContextNode;
import com.atlassian.jira.issue.customfields.CustomFieldType;
import com.atlassian.jira.issue.customfields.impl.*;
import com.atlassian.jira.issue.customfields.option.Option;
import com.atlassian.jira.issue.customfields.option.Options;
import com.atlassian.jira.issue.customfields.view.CustomFieldParamsImpl;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutItem;
import com.atlassian.jira.issue.history.ChangeItemBean;
import com.atlassian.jira.issue.label.Label;
import com.atlassian.jira.issue.label.LabelManager;
import com.atlassian.jira.issue.operation.IssueOperations;
import com.atlassian.jira.issue.priority.Priority;
import com.atlassian.jira.issue.util.DefaultIssueChangeHolder;
import com.atlassian.jira.issue.util.IssueUpdateBean;
import com.atlassian.jira.issue.util.IssueUpdater;
import com.atlassian.jira.mail.MailLoggingManager;
import com.atlassian.jira.mail.MailThreadManager;
import com.atlassian.jira.project.version.Version;
import com.atlassian.jira.project.version.VersionManager;
import com.atlassian.jira.security.PermissionManager;
import com.atlassian.jira.security.Permissions;
import com.atlassian.jira.security.roles.ProjectRole;
import com.atlassian.jira.security.roles.ProjectRoleManager;
import com.atlassian.jira.service.util.ServiceUtils;
import com.atlassian.jira.service.util.handler.MessageHandlerContext;
import com.atlassian.jira.service.util.handler.MessageUserProcessor;
import com.atlassian.jira.user.util.UserManager;
import com.easesolutions.jira.plugins.easemailhandler.util.EaseUtil;
import com.easesolutions.jira.plugins.easemailhandler.util.IssueUtil;
import com.easesolutions.jira.plugins.easemailhandler.util.RestrictedField;
import org.apache.commons.lang.exception.ExceptionUtils;

import javax.mail.Message;
import javax.mail.MessagingException;
import java.io.IOException;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

@ExperimentalApi
public abstract class AbstractCommentHandler extends AbstractMessageHandler
{
    private final PermissionManager permissionManager;
    private final IssueUpdater issueUpdater;

    /**
     * @deprecated Please use other constructor that explicitly sets dependencies
     */
    protected AbstractCommentHandler()
    {
        this(ComponentAccessor.getPermissionManager(),
                ComponentAccessor.getComponent(IssueUpdater.class),
                ComponentAccessor.getUserManager(),
                ComponentAccessor.getApplicationProperties(),
                ComponentAccessor.getComponent(JiraApplicationContext.class),
                ComponentAccessor.getComponent(MailLoggingManager.class),
                ComponentAccessor.getComponent(MessageUserProcessor.class));
    }

    /**
     * Deprecated Constructor.
     *
     * @deprecated Use {@link #AbstractCommentHandler(com.atlassian.jira.security.PermissionManager, com.atlassian.jira.issue.util.IssueUpdater, com.atlassian.jira.user.util.UserManager, com.atlassian.jira.config.properties.ApplicationProperties, com.atlassian.jira.JiraApplicationContext, com.atlassian.jira.mail.MailLoggingManager, com.atlassian.jira.service.util.handler.MessageUserProcessor)} instead. Since v5.0.
     */
    protected AbstractCommentHandler(PermissionManager permissionManager, IssueUpdater issueUpdater,
                                     ApplicationProperties applicationProperties,
                                     JiraApplicationContext jiraApplicationContext)
    {
        super(applicationProperties, jiraApplicationContext);
        this.permissionManager = permissionManager;
        this.issueUpdater = issueUpdater;
    }

    protected AbstractCommentHandler(PermissionManager permissionManager, IssueUpdater issueUpdater,
                                     UserManager userManager, ApplicationProperties applicationProperties,
                                     JiraApplicationContext jiraApplicationContext, MailLoggingManager mailLoggingManager, MessageUserProcessor messageUserProcessor)
    {
        super(userManager, applicationProperties, jiraApplicationContext, mailLoggingManager, messageUserProcessor);
        this.permissionManager = permissionManager;
        this.issueUpdater = issueUpdater;
    }

    @Override
    public boolean handleMessage(Message message, MessageHandlerContext context)
            throws MessagingException
    {
        if (!canHandleMessage(message, context.getMonitor()))
        {
            return deleteEmail;
        }

        try
        {
            String subject = message.getSubject();
            String body = getEmailBody(message);
            //if no body, just delete the mail
            if (body == null)
            {
                String error = "Message body is null";
                context.getMonitor().error(getI18nBean().getText(error));
                context.getMonitor().messageRejected(message, error);
                return false;
            }
            Map<String, String> fieldMap = EaseUtil.extractFields(body);
            Set<String> allFields = fieldMap.keySet();

            Issue issue = ServiceUtils.findIssueObjectInString(subject);

            if (issue == null)
            {
                String issuekeyString = fieldMap.get(EaseUtil.EASE_FIELD_ISSUEKEY);
                if (issuekeyString != null)
                {
                    issue = ServiceUtils.getIssueObject(issuekeyString.toUpperCase(Locale.getDefault()));
                }
                if (issue == null)
                {
                    String error = "The issuekey specified in email body is invalid: " + issuekeyString;
                    context.getMonitor().warning(error);
                    context.getMonitor().messageRejected(message, error);
                    return false;
                }
            }


            //if a valid issue is found
            if (issue != null)
            {
                //get either the sender of the message, or the default reporter
                User reporter = getReporter(message, context);

                //no reporter - so reject the message
                if (reporter == null)
                {
                    log.warn("The mail 'FROM' does not match a valid user");
                    log.warn("This user is not in jira so can not add a comment: " + message.getFrom()[0]);
                    final String text = getI18nBean().getText("The sender does not match a valid user in system. Issue cannot be commented.");
                    context.getMonitor().error(text);
                    context.getMonitor().messageRejected(message, text);
                    return false; // Don't delete an email if we don't deal with it.
                }


                if (context.isRealRun() && !permissionManager.hasPermission(Permissions.COMMENT_ISSUE, issue, reporter))
                {
                    log.warn(reporter.getDisplayName() + " does not have permission to comment on an issue in project: " + issue.getProjectObject().getId());
                    final String text = getI18nBean().getText("Sender does not have permission to comment on the issue.");
                    context.getMonitor().error(text);
                    context.getMonitor().messageRejected(message, text);
                    return false;
                }

                final Comment comment = context.createComment(issue, reporter, body, false);

                // Record the message id of this e-mail message so we can track replies to this message
                // from other recipients and associate them with this issue issue.
                recordMessageId(MailThreadManager.ISSUE_COMMENTED_FROM_EMAIL, message, issue.getId(), context);

                // Create attachments, if there are any attached to the message
                Collection<ChangeItemBean> attachmentsChangeItems = null;
                try
                {
                    attachmentsChangeItems = createAttachmentsForMessage(message, issue, context);
                } catch (IOException e)
                {
                    // failed to create attachment, but we still want to delete message
                    // no log required as the exception is already logged
                } catch (MessagingException e)
                {
                    // failed to create attachment, but we still want to delete message
                    // no log required as the exception is already logged
                }


                MutableIssue i = (MutableIssue) issue;
                CustomFieldManager cfm = ComponentManager.getInstance().getCustomFieldManager();
                boolean hasPermissionToEdit = permissionManager.hasPermission(Permissions.EDIT_ISSUE, issue, reporter);
                if (allFields.size() > 0 && !hasPermissionToEdit)
                {
                    log.warn(reporter.getDisplayName() + " does not have permission to edit on an issue in project: " + issue.getLong("project"));
                    context.getMonitor().error((getI18nBean().getText("Sender does not have permission to edit  the issue.")));
                    return false;
                }
                if (hasPermissionToEdit)
                {
                    //get the edit issue screen, only allow user to edit fields that appear in the edit issue screen.
                    List<String> fieldStringInEditIssueScreen = EaseUtil.getFieldStringInIssueOperationForm(issue
                            ,IssueOperations.EDIT_ISSUE_OPERATION);
                    log.debug("fieldStringInEditIssueScreen ="+fieldStringInEditIssueScreen);
                    // get required field, these fields should not be set to empty
                    List<String> requiredFieldString = EaseUtil.getRequiredFieldString(issue);


                    //2. re-set jira fields
                    for (String f : allFields)
                    {
                        if(!fieldStringInEditIssueScreen.contains(f))
                        {
                            continue;
                        }

                        String fValue = fieldMap.get(f);
                        fValue = (fValue == null) ? "" : fValue.trim();

                        //should allow to clear a field if it is not required
                        if (fValue.isEmpty() && requiredFieldString.contains(f))
                        {
                            continue;
                        }
                        if (f.equalsIgnoreCase(EaseUtil.EASE_FIELD_SUMMARY))
                        {
                            String oldValue = issue.getSummary();
                            String newValue = fValue;
                            ModifiedValue modifiedValue = new ModifiedValue(oldValue, newValue);
                            attachmentsChangeItems.add(new ChangeItemBean("jira", IssueFieldConstants.SUMMARY, null, oldValue, null, newValue));
                            i.setSummary(newValue);
                        } else if (f.equalsIgnoreCase(EaseUtil.EASE_FIELD_DESCRIPTION))
                        {
                            String oldValue = i.getDescription();
                            String newValue = fValue;
                            ModifiedValue modifiedValue = new ModifiedValue(oldValue, newValue);
                            attachmentsChangeItems.add(new ChangeItemBean("jira", IssueFieldConstants.DESCRIPTION, null, oldValue, null, newValue));
                            i.setDescription(newValue);
                        }
                        else if (f.equalsIgnoreCase(EaseUtil.EASE_FIELD_ISSUETYPE))
                        {
                            //should not allow to edit issue type, as sometimes it can only be move issue to new issue type
                        }
                        else if (f.equalsIgnoreCase(EaseUtil.EASE_FIELD_PRIORITY))
                        {
                            String oldValue = i.getPriorityObject().getName();
                            String oldId = i.getPriorityObject().getId();
                            String newValue = null;
                            String newId = null;
                            if(fValue.isEmpty())
                            {
                                i.setPriority(null);
                                attachmentsChangeItems.add(new ChangeItemBean("jira", IssueFieldConstants.PRIORITY, oldId, oldValue, newId, newValue));
                            }
                            else
                            {
                                Collection collection = ComponentManager.getInstance().getConstantsManager().getPriorityObjects();
                                for (Iterator iterator = collection.iterator(); iterator.hasNext(); )
                                {
                                    Priority p = (Priority) iterator.next();
                                    if ((p.getName().equalsIgnoreCase(fValue)))
                                    {
                                        i.setPriority(p.getGenericValue());
                                        newValue = p.getName();
                                        newId = p.getId();
                                        attachmentsChangeItems.add(new ChangeItemBean("jira", IssueFieldConstants.PRIORITY, oldId, oldValue, newId, newValue));
                                    }
                                }
                            }

                        } else if (f.equalsIgnoreCase(EaseUtil.EASE_FIELD_ENVIRONMENT))
                        {
                            String oldValue = i.getEnvironment();
                            String newValue = fValue;
                            attachmentsChangeItems.add(new ChangeItemBean("jira", IssueFieldConstants.ENVIRONMENT, null, oldValue, null, newValue));
                            i.setEnvironment(newValue);
                        } else if (f.equalsIgnoreCase(EaseUtil.EASE_FIELD_DUEDATE))
                        {
                            //check if the sender has schedule issue permission
                            if(!permissionManager.hasPermission(Permissions.SCHEDULE_ISSUE,i.getProjectObject(),reporter))
                            {
                                log.warn("user has no schedule issue permission, ignored field "+f);
                                continue;
                            }

                            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
                            String oldValue = "";
                            if(i.getDueDate()!=null)
                            {
                                oldValue = sdf.format(new Date(i.getDueDate().getTime()));
                            }
                            if(fValue.isEmpty())
                            {
                                attachmentsChangeItems.add(new ChangeItemBean("jira", IssueFieldConstants.DUE_DATE, oldValue, oldValue, null, null));
                                i.setDueDate(null);
                            }
                            else
                            {
                                try
                                {
                                    Date d = sdf.parse(fValue);
                                    Timestamp tsm = new Timestamp(d.getTime());
                                    String newValue = sdf.format(d);
                                    attachmentsChangeItems.add(new ChangeItemBean("jira", IssueFieldConstants.DUE_DATE, oldValue, oldValue, newValue, newValue));
                                    i.setDueDate(tsm);
                                }
                                catch (ParseException ex)
                                {

                                }
                            }

                        } else if (f.equalsIgnoreCase(EaseUtil.EASE_FIELD_AFFECTEDVERSION))
                        {
                            if(fValue.isEmpty())
                            {
                                i.setAffectedVersions(new ArrayList<Version>());
                                ComponentAccessor.getIssueManager().updateIssue(reporter, i, EventDispatchOption.ISSUE_UPDATED, true);
                            }
                            else
                            {
                                VersionManager vm = ComponentAccessor.getVersionManager();
                                List<Version> validVersion = new ArrayList<Version>();
                                String[] versionsFValues = fValue.split(",");
                                for (String v : versionsFValues)
                                {
                                    Version version = vm.getVersion(i.getProjectObject().getId(), v);
                                    if (version != null)
                                    {
                                        validVersion.add(version);
                                    }
                                }
                                if (validVersion.size() > 0)
                                {
                                    i.setAffectedVersions(validVersion);
                                    ComponentAccessor.getIssueManager().updateIssue(reporter, i, EventDispatchOption.ISSUE_UPDATED, true);
                                }
                            }

                        } else if (f.equalsIgnoreCase(EaseUtil.EASE_FIELD_FIXVERSION))
                        {
                            //check if the sender has resolve issue permission
                            if(!permissionManager.hasPermission(Permissions.RESOLVE_ISSUE,i.getProjectObject(),reporter))
                            {
                                log.warn("user has no resolve issue permission, ignored field "+f);
                                continue;
                            }

                            if(fValue.isEmpty())
                            {
                                i.setFixVersions(new ArrayList<Version>());
                                ComponentAccessor.getIssueManager().updateIssue(reporter, i, EventDispatchOption.ISSUE_UPDATED, true);
                            }
                            else
                            {
                                VersionManager vm = ComponentAccessor.getVersionManager();
                                List<Version> validVersion = new ArrayList<Version>();
                                String[] versionsFValues = fValue.split(",");
                                for (String v : versionsFValues)
                                {
                                    Version version = vm.getVersion(i.getProjectObject().getId(), v);
                                    if (version != null)
                                    {
                                        validVersion.add(version);
                                    }
                                }
                                if (validVersion.size() > 0)
                                {
                                    i.setFixVersions(validVersion);
                                    ComponentAccessor.getIssueManager().updateIssue(reporter, i, EventDispatchOption.ISSUE_UPDATED, true);
                                }
                            }
                        }
                        else if (f.equalsIgnoreCase(EaseUtil.EASE_FIELD_COMPONENTS))
                        {
                            if(fValue.isEmpty())
                            {
                                i.setComponentObjects(null);
                                ComponentAccessor.getIssueManager().updateIssue(reporter, i, EventDispatchOption.ISSUE_UPDATED, true);
                            }
                            else
                            {
                                ProjectComponentManager projectComponentManager = ComponentManager.getInstance().
                                        getProjectComponentManager();

                                Collection projectComponents = issue.getProjectObject().getProjectComponents();
                                String[] componentsFValues = fValue.split(",");
                                Collection<ProjectComponent> componentsList = new ArrayList<ProjectComponent>();
                                for (int j = 0; j < componentsFValues.length; j++)
                                {
                                    String c = componentsFValues[j].trim();
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
                                    i.setComponentObjects(componentsList);
                                    ComponentAccessor.getIssueManager().updateIssue(reporter, i, EventDispatchOption.ISSUE_UPDATED, true);
                                }
                            }
                        } else if (f.equalsIgnoreCase(EaseUtil.EASE_FIELD_LABELS))
                        {
                            Set<Label> oldLabels = i.getLabels();
                            String oldValue = "";
                            for (Label l : oldLabels)
                            {
                                oldValue += l + " ";
                            }
                            oldValue = oldValue.trim();

                            if(fValue.isEmpty())
                            {

                                LabelManager lm = ComponentManager.getComponent(LabelManager.class);
                                lm.setLabels(reporter, issue.getId(), Collections.EMPTY_SET, false, false);
                                attachmentsChangeItems.add(new ChangeItemBean("jira", IssueFieldConstants.LABELS, null, oldValue, null, null));
                            }
                            else
                            {
                                String[] labelsString = fValue.split(",");
                                String newValue = "";


                                Set<String> labelStringSet = new LinkedHashSet<String>();
                                for (String lb : labelsString)
                                {
                                    if (lb.trim().length() > 0)
                                    {
                                        labelStringSet.add(lb.trim());
                                        newValue += lb.trim() + " ";
                                    }
                                }
                                newValue = newValue.trim();

                                LabelManager lm = ComponentManager.getComponent(LabelManager.class);
                                lm.setLabels(reporter, issue.getId(), labelStringSet, false, false);

                                attachmentsChangeItems.add(new ChangeItemBean("jira", IssueFieldConstants.LABELS, null, oldValue, null, newValue));
                            }
                        } else
                        {

                        }
                    }

                    for (String f : allFields)
                    {
                        log.info("processing field "+f);
                        if(!fieldStringInEditIssueScreen.contains(f))
                        {
                            log.info("it is not in edit issue screen, so ignore");
                            continue;
                        }

                        String fValue = fieldMap.get(f);
                        fValue = (fValue == null) ? "" : fValue.trim();
                        if (fValue.isEmpty() && requiredFieldString.contains(f))
                        {
                            continue;
                        }
                        if (!EaseUtil.isSystemFields(f))
                        {
                            // check if it is a restricted field
                            String lowerProjectKey = i.getProjectObject().getKey().toLowerCase();

                            List<RestrictedField> restrictedFields = configUtil.getProjectsRestrictedFields().get(lowerProjectKey);

                            boolean restricted = false;
                            boolean allowed = false;
                            Long fieldId = 0L;

                            if (restrictedFields != null)
                            {
                                ProjectRoleManager prm = (ProjectRoleManager) ComponentManager.getComponent(ProjectRoleManager.class);
                                Collection<ProjectRole> proles = prm.getProjectRoles(reporter, i.getProjectObject());

                                for (RestrictedField rf : restrictedFields)
                                {
                                    String rfName = rf.getFieldLowerName();
                                    if (f.equalsIgnoreCase(rfName))
                                    {
                                        if (rf.getAllowReporter().equalsIgnoreCase("true"))
                                        {
                                            if (i.getReporter().getName().equalsIgnoreCase(reporter.getName()))
                                            {
                                                allowed = true;

                                            }
                                        }
                                        if (!allowed)
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
                                    cf = IssueUtil.getCorrectCustomField(cfm, i, f);
                                }
                            } else
                            {
                                cf = IssueUtil.getCorrectCustomField(cfm, i, f);
                            }

                            if (cf != null)
                            {
                                Object oldValue = i.getCustomFieldValue(cf);

                                if(fValue.isEmpty())
                                {
                                    ModifiedValue mf = new ModifiedValue(oldValue, null);
                                    FieldLayoutItem fieldLayoutItem =
                                            ComponentManager.getInstance().getFieldLayoutManager().getFieldLayout(i).getFieldLayoutItem(cf);
                                    cf.updateValue(fieldLayoutItem, i, mf, new DefaultIssueChangeHolder());

                                    String from = IssueUtil.getStringifiedVal(oldValue);
                                    String to = null;

                                    ChangeItemBean cfCIB = new ChangeItemBean("custom", cf.getName(), null, from, null, to);
                                    attachmentsChangeItems.add(cfCIB);
                                }
                                else
                                {

                                    Object newValue = null;
                                    CustomFieldType cfType = cf.getCustomFieldType();
                                    if (cfType instanceof MultiSelectCFType)
                                    {
                                        String[] newOptions = fValue.split(",");
                                        List<Option> nominatedOptions = new ArrayList<Option>();
                                        List schemes = cf.getConfigurationSchemes();
                                        JiraContextNode jiraContextNode = (JiraContextNode) ((FieldConfigScheme) schemes.get(0)).getContexts().get(0);
                                        Options allOptions = ((MultiSelectCFType) cfType).getOptions(cf.getRelevantConfig(i), jiraContextNode);
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
                                        newValue = nominatedOptions;
                                        if (nominatedOptions.size() == 0)
                                        {
                                            newValue = null;
                                        }
                                        //i.setCustomFieldValue(cf,newValue);
                                    }
                                    else if (cfType instanceof SelectCFType)
                                    {
                                        List schemes = cf.getConfigurationSchemes();
                                        JiraContextNode jiraContextNode = (JiraContextNode) ((FieldConfigScheme) schemes.get(0)).getContexts().get(0);
                                        Options allOptions = ((SelectCFType) cfType).getOptions(cf.getRelevantConfig(i), jiraContextNode);
                                        for (Iterator iterator = allOptions.iterator(); iterator.hasNext(); )
                                        {
                                            Option option = (Option) iterator.next();
                                            if (option.getValue().equalsIgnoreCase(fValue))
                                            {
                                                newValue = option;
                                            }
                                        }
                                    }
                                    else if (cfType instanceof TextCFType)
                                    {
                                        newValue = cfType.getSingularObjectFromString(fValue);
                                        //i.setCustomFieldValue(cf,newValue);
                                    }
                                    else if (cfType instanceof GenericTextCFType)
                                    {
                                        newValue = cfType.getSingularObjectFromString(fValue);
                                    }
                                    else if (cfType instanceof NumberCFType)
                                    {
                                        newValue = cfType.getSingularObjectFromString(fValue);
                                    }
                                    else if (cfType instanceof LabelsCFType)
                                    {


                                        Set<Label> defaultLabels = new LinkedHashSet<Label>();
                                        String[] labelsString = fValue.split(",");
                                        for (int count = 0; count < labelsString.length; count++)
                                        {
                                            String v = labelsString[count].trim();
                                            if (v.length() > 0)
                                            {
                                                defaultLabels.add((Label) cfType.getSingularObjectFromString(labelsString[count].trim()));
                                            }
                                        }

                                        newValue = defaultLabels;
                                        if (defaultLabels.size() == 0)
                                        {
                                            newValue = null;
                                        }
                                    }
                                    else if (cfType instanceof MultiUserCFType)
                                    {
                                        String[] list = fValue.split(",");
                                        List<String> usersStringList = Arrays.asList(list);
                                        List<String> validUsersStringList = new ArrayList<String>();
                                        for (String userstring : usersStringList)
                                        {
                                            User u = messageUserProcessor.findUserByUsername(userstring.trim());
                                            if (u != null)
                                            {
                                                validUsersStringList.add(userstring);
                                            }
                                            else
                                            {
                                                log.warn("Found invalid username when process MultiUserCFType, username=" + userstring);
                                            }
                                        }
                                        CustomFieldParamsImpl a = new CustomFieldParamsImpl(cf, validUsersStringList);
                                        newValue = cfType.getValueFromCustomFieldParams(a);
                                    }
                                    //todo  the other types
                                    else
                                    {
                                        continue;
                                    }
                                    if (newValue != null)
                                    {
                                        ModifiedValue mf = new ModifiedValue(oldValue, newValue);
                                        FieldLayoutItem fieldLayoutItem =
                                                ComponentManager.getInstance().getFieldLayoutManager().getFieldLayout(i).getFieldLayoutItem(cf);
                                        cf.updateValue(fieldLayoutItem, i, mf, new DefaultIssueChangeHolder());

                                        String from = IssueUtil.getStringifiedVal(oldValue);
                                        String to = IssueUtil.getStringifiedVal(newValue);


                                        ChangeItemBean cfCIB = new ChangeItemBean("custom", cf.getName(), null, from, null, to);
                                        attachmentsChangeItems.add(cfCIB);
                                    }
                                }
                            }
                        }
                    }
                }

                //set toField and ccField to record the to and cc addresses in incoming email
                CustomField toCf = getToCf();
                if(toCf!=null)
                {
                    String toFieldValue = getAddressesString(message.getRecipients(Message.RecipientType.TO),mailBoxEmail);
                    if(!toFieldValue.isEmpty())
                    {
                        String newValue;
                        String oldValue = (String)i.getCustomFieldValue(toCf);
                        if(oldValue!=null)
                        {
                            String[] addresses = oldValue.split(",");
                            String[] newAddresses = toFieldValue.split(",");
                            newValue = mergeString(addresses,newAddresses);
                        }
                        else
                        {
                            newValue = toFieldValue;
                        }

                        ModifiedValue mf = new ModifiedValue(oldValue, newValue);
                        FieldLayoutItem fieldLayoutItem =
                                ComponentManager.getInstance().getFieldLayoutManager().getFieldLayout(i).getFieldLayoutItem(toCf);
                        toCf.updateValue(fieldLayoutItem, i, mf, new DefaultIssueChangeHolder());

                        String from = IssueUtil.getStringifiedVal(oldValue);
                        String to = IssueUtil.getStringifiedVal(newValue);


                        ChangeItemBean cfCIB = new ChangeItemBean("custom", toCf.getName(), null, from, null, to);
                        attachmentsChangeItems.add(cfCIB);
                    }
                }

                CustomField ccCf = getCcCf();
                if(ccCf!=null)
                {
                    String ccFieldValue = getAddressesString(message.getRecipients(Message.RecipientType.CC),mailBoxEmail);
                    if(!ccFieldValue.isEmpty())
                    {
                        String newValue;
                        String oldValue = (String)i.getCustomFieldValue(ccCf);
                        if(oldValue!=null)
                        {
                            String[] addresses = oldValue.split(",");
                            String[] newAddresses = ccFieldValue.split(",");
                            newValue = mergeString(addresses,newAddresses);
                        }
                        else
                        {
                            newValue = ccFieldValue;
                        }

                        ModifiedValue mf = new ModifiedValue(oldValue, newValue);
                        FieldLayoutItem fieldLayoutItem =
                                ComponentManager.getInstance().getFieldLayoutManager().getFieldLayout(i).getFieldLayoutItem(ccCf);
                        ccCf.updateValue(fieldLayoutItem, i, mf, new DefaultIssueChangeHolder());

                        String from = IssueUtil.getStringifiedVal(oldValue);
                        String to = IssueUtil.getStringifiedVal(newValue);


                        ChangeItemBean cfCIB = new ChangeItemBean("custom", ccCf.getName(), null, from, null, to);
                        attachmentsChangeItems.add(cfCIB);
                    }
                }



                if (context.isRealRun())
                {
                    update(attachmentsChangeItems, issue, reporter, comment);
                }

                return true; //delete message as we've left a comment now


            }
            else
            {
                context.getMonitor().error(getI18nBean().getText("admin.errors.no.corresponding.issue"));
            }
        }
        catch (PermissionException e)
        {
            log.warn("PermissionException creating comment " + e.getMessage(), e);
            context.getMonitor().error(getI18nBean().getText("admin.errors.no.comment.permission", e.getMessage()), e);
        }
        catch (Exception e)
        {
            log.error("MessagingException creating comment " + e.getMessage(), e);
            e.printStackTrace();
            String errorMsg = "Error when processing email to comment or edit issue.\n";
            String[] rootCauses = ExceptionUtils.getRootCauseStackTrace(e);
            if(rootCauses!=null)
            {
                errorMsg+="RootCause: "+rootCauses[0];
            }
            else
            {
                errorMsg = errorMsg + ExceptionUtils.getStackTrace(e);
            }

            context.getMonitor().error(getI18nBean().getText(errorMsg, e));
        }
        return false; // Dont delete message
    }

    private void update(Collection<ChangeItemBean> attachmentsChangeItems, Issue issue, User reporter, Comment comment)
            throws JiraException
    {
        // Get the eventTypeId to dispatch
        Long eventTypeId = getEventTypeId(attachmentsChangeItems);

        // Need to update the Updated Date of an issue and dispatch an event
        IssueUpdateBean issueUpdateBean = new IssueUpdateBean(issue, issue, eventTypeId, reporter);
        // Set the comment on is issueUpdateBean such that the disptached event will have access to it.
        // The comment is also needed for generating notification e-mails
        issueUpdateBean.setComment(comment);
        if (attachmentsChangeItems != null && !attachmentsChangeItems.isEmpty())
        {
            // If there were attachments added, add their change items to the issueUpdateBean
            issueUpdateBean.setChangeItems(attachmentsChangeItems);
        }

        issueUpdateBean.setDispatchEvent(true);
        issueUpdateBean.setParams(EasyMap.build("eventsource", IssueEventSource.ACTION));
        // Do not let the issueUpdater generate change items. We have already generated all the needed ones.
        // So pass in 'false'.
        issueUpdater.doUpdate(issueUpdateBean, false);
    }

    /**
     * If there are attachments added dispatch {@link EventType#ISSUE_UPDATED_ID}, otherwise
     * dispatch {@link EventType#ISSUE_COMMENTED_ID}.
     */
    public static Long getEventTypeId(Collection attachmentsChangeItems)
    {
        // If we are only adding a comment then dispatch the ISSUE COMMENTED event
        Long eventTypeId = EventType.ISSUE_COMMENTED_ID;
        if (attachmentsChangeItems != null && !attachmentsChangeItems.isEmpty())
        {
            // If we are also adding atatchments then dispatch the ISSUE UPDATED event instead
            eventTypeId = EventType.ISSUE_UPDATED_ID;
        }
        return eventTypeId;
    }

    protected abstract String getEmailBody(Message message) throws MessagingException;
}
