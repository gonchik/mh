package com.easesolutions.jira.plugins.easemailhandler.handler;


import com.atlassian.core.util.ClassLoaderUtils;
import com.atlassian.jira.JiraApplicationContext;
import com.atlassian.jira.config.properties.ApplicationProperties;
import com.atlassian.jira.issue.IssueFactory;
import com.atlassian.jira.issue.comments.CommentManager;
import com.atlassian.jira.issue.util.IssueUpdater;
import com.atlassian.jira.mail.MailLoggingManager;
import com.atlassian.jira.security.PermissionManager;
import com.atlassian.jira.service.util.handler.MessageUserProcessor;
import com.atlassian.jira.user.util.UserManager;
import com.atlassian.mail.MailUtils;
import com.easesolutions.jira.plugins.easemailhandler.util.StripQuoteUtils;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Part;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.StringTokenizer;

/**
 * This handler adds the body of the email as a comment, using the subject
 * to determine which issue to add the comment to.
 * <p/>
 * The difference between this and FullCommentHandler is that this will
 * strip any quoted lines from the email (ie lines that start with > or |).
 *
 * @see FullCommentHandler
 */
public class NonQuotedCommentHandler extends AbstractCommentHandler
{
    private static final String OUTLOOK_QUOTED_FILE = "outlook-email.translations";
    private Collection messages;

    public NonQuotedCommentHandler()
    {
    }

    public NonQuotedCommentHandler(PermissionManager permissionManager, IssueUpdater issueUpdater,
            UserManager userManager, ApplicationProperties applicationProperties,
            JiraApplicationContext jiraApplicationContext, MailLoggingManager mailLoggingManager,
            MessageUserProcessor messageUserProcessor)
    {
        super(permissionManager, issueUpdater, userManager, applicationProperties, jiraApplicationContext, mailLoggingManager, messageUserProcessor);
    }

    /**
     * Given a message - this handler will add the entire message body as a comment to
     * the first issue referenced in the subject.
     */
    protected String getEmailBody(Message message) throws MessagingException
    {
        StripQuoteUtils u = new StripQuoteUtils();
        return u.stripQuotedLines(MailUtils.getBody(message));
    }


    /**
     * Plain text parts must be kept if they arent empty.
     *
     * @param part The part being tested.
     * @return Returns true if the part content is not empty, otherwise returns false.
     */
    protected boolean attachPlainTextParts(final Part part) throws MessagingException, IOException
    {
        return !MailUtils.isContentEmpty(part);
    }

    /**
     * Comments never wish to keep html parts that are not attachments as they extract the plain text
     * part and use that as the content. This method therefore is hard wired to always return false.
     *
     * @param part The part being tested.
     * @return Always returns false
     * @throws MessagingException
     * @throws IOException
     */
    protected boolean attachHtmlParts(final Part part) throws MessagingException, IOException
    {
        return false;
    }
}
