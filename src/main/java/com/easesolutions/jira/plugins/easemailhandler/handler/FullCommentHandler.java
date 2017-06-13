package com.easesolutions.jira.plugins.easemailhandler.handler;

import com.atlassian.jira.JiraApplicationContext;
import com.atlassian.jira.config.properties.ApplicationProperties;
import com.atlassian.jira.issue.util.IssueUpdater;
import com.atlassian.jira.mail.MailLoggingManager;
import com.atlassian.jira.security.PermissionManager;
import com.atlassian.jira.service.util.handler.MessageUserProcessor;
import com.atlassian.jira.user.util.UserManager;
import com.atlassian.mail.MailUtils;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Part;
import java.io.IOException;

public class FullCommentHandler extends AbstractCommentHandler
{
    public FullCommentHandler()
    {
    }

    public FullCommentHandler(PermissionManager permissionManager, IssueUpdater issueUpdater, UserManager userManager, ApplicationProperties applicationProperties, JiraApplicationContext jiraApplicationContext, MailLoggingManager mailLoggingManager, MessageUserProcessor messageUserProcessor)
    {
        super(permissionManager, issueUpdater, userManager, applicationProperties, jiraApplicationContext, mailLoggingManager, messageUserProcessor);
    }

    /**
     * Given a message - this handler will add the entire message body as a comment to
     * the first issue referenced in the subject.
     */
    protected String getEmailBody(Message message) throws MessagingException
    {
        return MailUtils.getBody(message);
    }

    /**
     * Plain text parts must be kept if they arent empty.
     *
     * @param part The plain text part.
     * @return True if the part is not empty, otherwise returns false
     */
    protected boolean attachPlainTextParts(final Part part) throws MessagingException, IOException
    {
        return !MailUtils.isContentEmpty(part);
    }

    /**
     * Comments never wish to keep html parts that are not attachments as they extract the plain text
     * part and use that as the content. This method therefore is hard wired to always return false.
     *
     * @param part The html part being processed.
     * @return Always returns false
     * @throws MessagingException
     * @throws IOException
     */
    protected boolean attachHtmlParts(final javax.mail.Part part) throws MessagingException, IOException
    {
        return false;
    }
}
