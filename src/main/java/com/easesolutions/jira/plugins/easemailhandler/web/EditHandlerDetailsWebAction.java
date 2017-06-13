package com.easesolutions.jira.plugins.easemailhandler.web;


import com.atlassian.configurable.ObjectConfigurationException;

import com.atlassian.jira.plugins.mail.webwork.AbstractEditHandlerDetailsWebAction;
import com.atlassian.jira.service.JiraServiceContainer;
import com.atlassian.jira.service.services.file.AbstractMessageHandlingService;
import com.atlassian.jira.service.services.mail.MailFetcherService;
import com.atlassian.jira.service.util.ServiceUtils;
import com.atlassian.jira.util.collect.MapBuilder;
import com.atlassian.plugin.PluginAccessor;
import com.atlassian.sal.api.websudo.WebSudoRequired;
import com.easesolutions.jira.plugins.easemailhandler.handler.AbstractMessageHandler;
import com.easesolutions.jira.plugins.easemailhandler.handler.CreateIssueHandler;
import com.easesolutions.jira.plugins.easemailhandler.handler.CreateOrCommentHandler;
import com.google.common.collect.Maps;
import com.easesolutions.jira.plugins.easemailhandler.util.ConfigUtil;
import java.util.Map;

@WebSudoRequired
public class EditHandlerDetailsWebAction extends AbstractEditHandlerDetailsWebAction
{
    private String bulk="";
    private String forwardEmail = "";
    private String createNewUser ="";
    private String newUserGroup ="";
    private String mailBoxEmail = "";


    public EditHandlerDetailsWebAction(
            PluginAccessor pluginAccessor)
    {
        super(pluginAccessor);

    }

    @Override
    protected void copyServiceSettings(JiraServiceContainer serviceContainer) throws ObjectConfigurationException
    {
        final String params = serviceContainer.getProperty(AbstractMessageHandlingService.KEY_HANDLER_PARAMS);
		final Map<String, String> parameterMap = ServiceUtils.getParameterMap(params);

        bulk = parameterMap.get(CreateOrCommentHandler.KEY_BULK);
        forwardEmail = parameterMap.get("forwardEmail");
        createNewUser = parameterMap.get(CreateOrCommentHandler.KEY_CREATEUSERS);
        newUserGroup = parameterMap.get(CreateOrCommentHandler.KEY_NEW_USER_GROUP_NAME);
        mailBoxEmail = parameterMap.get(AbstractMessageHandler.KEY_MAILBOX_EMAIL);
    }


    @Override
    protected Map<String, String> getHandlerParams()
    {
        Map<String, String> res = Maps.newLinkedHashMap();
        res.put(CreateOrCommentHandler.KEY_BULK,bulk);
        res.put("forwardEmail",forwardEmail);
        res.put(CreateOrCommentHandler.KEY_CREATEUSERS,createNewUser);
        res.put(CreateOrCommentHandler.KEY_NEW_USER_GROUP_NAME,newUserGroup);
        res.put(AbstractMessageHandler.KEY_MAILBOX_EMAIL,mailBoxEmail);

        return res;
    }

    @Override
    protected Map<String, String[]> getAdditionalServiceParams() throws Exception
    {
        return MapBuilder.<String, String[]>newBuilder(MailFetcherService.FORWARD_EMAIL, new String[] { forwardEmail })
                .toMutableMap();
    }

    @Override
    protected void doValidation()
    {
        if (configuration == null) {
			return; // short-circuit in case we lost session, goes directly to doExecute which redirects user
		}
		super.doValidation();

    }


    public String getBulk() {
        return bulk;
    }

    public String getForwardEmail() {
        return forwardEmail;
    }



    public void setBulk(String bulk) {
        this.bulk = bulk;
    }

    public void setForwardEmail(String forwardEmail) {
        this.forwardEmail = forwardEmail;
    }

    public String getCreateNewUser()
    {
        return createNewUser;
    }

    public void setCreateNewUser(String createNewUser)
    {
        this.createNewUser = createNewUser;
    }

    public String getNewUserGroup()
    {
        return newUserGroup;
    }

    public void setNewUserGroup(String newUserGroup)
    {
        this.newUserGroup = newUserGroup;
    }

    public String getMailBoxEmail()
    {
        return mailBoxEmail;
    }

    public void setMailBoxEmail(String mailBoxEmail)
    {
        this.mailBoxEmail = mailBoxEmail;
    }
}
