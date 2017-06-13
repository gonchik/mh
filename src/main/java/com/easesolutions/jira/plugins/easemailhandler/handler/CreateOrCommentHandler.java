package com.easesolutions.jira.plugins.easemailhandler.handler;


import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.service.util.ServiceUtils;
import com.atlassian.jira.service.util.handler.MessageHandlerContext;
import com.atlassian.jira.service.util.handler.MessageHandlerErrorCollector;
import com.atlassian.jira.service.util.handler.MessageUserProcessor;
import com.atlassian.mail.MailUtils;
import com.easesolutions.jira.plugins.easemailhandler.util.EaseUtil;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Part;
import java.io.IOException;
import java.util.Map;

/**
 * A message handler to create a new issue, or add a comment
 * to an existing issue, from an incoming message. If the subject
 * contains a project key the message is added as a comment to that
 * issue. If no project key is found, a new issue is created in the
 * default project.
 */
public class CreateOrCommentHandler extends AbstractMessageHandler
{
    /**
     * Default project where new issues are created.
     */
    public String projectKey;

    /**
     * Default type for new issues.
     */
    public String issueType;

    /**
     * If set (to anything except "false"), quoted text is removed from comments.
     */
    public String stripquotes;
    public static final String KEY_PROJECT = "project";
    public static final String KEY_ISSUETYPE = "issuetype";
    public static final String KEY_QUOTES = "stripquotes";
    private static final String FALSE = "false";

    public boolean handleMessage(Message message, MessageHandlerContext context)
            throws MessagingException
    {
        String subject = message.getSubject();

        if (!canHandleMessage(message, context.getMonitor()))
        {
            if (log.isDebugEnabled())
            {
                log.debug("Cannot handle message '" + subject + "'.");
            }
            return deleteEmail;
        }


        String body = MailUtils.getBody(message);
        boolean createIssue= true;
        if(subjectContainsCreateIssueSyntax(subject))
        {
            createIssue = true;
        }
        else if(subjectContainsCommentIssueSyntax(subject))
        {
            createIssue = false;
        }
        else if(bodyContainsCreateIssueSyntax(body))
        {
            createIssue = true;
        }
        else if(bodyContainsCommentIssueSyntax(body))
        {
            createIssue=false;
        }
        else
        {
            createIssue = true;
        }

        if(createIssue)
        {
            CreateIssueHandler createIssueHandler = new CreateIssueHandler()
            {
                @Override
                protected MessageUserProcessor getMessageUserProcessor()
                {
                    return CreateOrCommentHandler.this.getMessageUserProcessor();
                }
            };

            createIssueHandler.init(params, context.getMonitor());
            return createIssueHandler.handleMessage(message, context);
        }
        //comment issue
        else
        {
            boolean doDelete;

            //add the message as a comment to the issue
            if ((stripquotes == null) || FALSE.equalsIgnoreCase(stripquotes)) //if stripquotes not defined in setup
            {
                FullCommentHandler fc = new FullCommentHandler()
                {
                    @Override
                    protected MessageUserProcessor getMessageUserProcessor()
                    {
                        return CreateOrCommentHandler.this.getMessageUserProcessor();
                    }
                };
                fc.init(params, context.getMonitor());
                doDelete = fc.handleMessage(message, context); //get message with quotes
            }
            else
            {
                NonQuotedCommentHandler nq = new NonQuotedCommentHandler()
                {
                    @Override
                    protected MessageUserProcessor getMessageUserProcessor()
                    {
                        return CreateOrCommentHandler.this.getMessageUserProcessor();
                    }
                };

                nq.init(params, context.getMonitor());
                doDelete = nq.handleMessage(message, context); //get message without quotes
            }
            return doDelete;
        }
    }

    public void init(Map<String, String> params, MessageHandlerErrorCollector errorCollector)
    {
        log.debug("CreateOrCommentHandler.init(params: " + params + ")");

        super.init(params, errorCollector);

        if (params.containsKey(KEY_PROJECT))
        {
            projectKey = params.get(KEY_PROJECT);
        }

        if (params.containsKey(KEY_ISSUETYPE))
        {
            issueType = params.get(KEY_ISSUETYPE);
        }

        if (params.containsKey(KEY_QUOTES))
        {
            stripquotes = params.get(KEY_QUOTES);
        }
    }

    /**
     * Plain text parts must be kept if they are not empty.
     *
     * @param part The plain text part being tested.
     * @return Returns true to attach false otherwise.
     */
    protected boolean attachPlainTextParts(final Part part) throws MessagingException, IOException
    {
        return !MailUtils.isContentEmpty(part);
    }

    /**
     * Comments never wish to keep html parts that are not attachments as they extract the plain text
     * part and use that as the content. This method therefore is hard wired to always return false.
     *
     * @param part The html part being processed
     * @return Always return false.
     * @throws MessagingException
     * @throws IOException
     */
    protected boolean attachHtmlParts(final Part part) throws MessagingException, IOException
    {
        return false;
    }
    //if subject contanis like [XXX-new], or email body contains @projectkey=XXX, or nothing in subject or body
    private boolean containsCreateIssueSyntax(Message message) throws MessagingException
    {
        String subject = message.getSubject();
        String body = MailUtils.getBody(message);
        Project p = EaseUtil.findProjectObjectInString(subject);
        if(p!=null)
        {
            if(log.isDebugEnabled())
            {
                log.debug("Found new issue syntax in subject.subject="+subject+". Project="+p.getKey());
            }
            return true;
        }
        Map<String,String> fieldMap = EaseUtil.extractFields(body);
        if(fieldMap.containsKey(EaseUtil.EASE_FIELD_PROJECTKEY))
        {
            if(log.isDebugEnabled())
            {
                log.debug("Found new issue syntax ("+EaseUtil.EASE_FIELD_PROJECTKEY+"="+fieldMap.get(EaseUtil.EASE_FIELD_PROJECTKEY)+ ")in body.");
            }
            return true;
        }

        return false;
    }
    private boolean containsCommentIssueSyntax(Message message) throws MessagingException
    {
        String subject = message.getSubject();
        String body = MailUtils.getBody(message);
        Issue i = EaseUtil.findIssueObjectInString(subject);
        if(i!=null)
        {
            if(log.isDebugEnabled())
            {
                log.debug("Found issue key in subject. subject="+subject+". Issue key="+i.getKey());
            }
            return true;
        }
        Map<String,String> fieldMap = EaseUtil.extractFields(body);
        if(fieldMap.containsKey(EaseUtil.EASE_FIELD_ISSUEKEY))
        {
            if(log.isDebugEnabled())
            {
                log.debug("Found issuekey syntax ("+EaseUtil.EASE_FIELD_ISSUEKEY+"="+fieldMap.get(EaseUtil.EASE_FIELD_ISSUEKEY)+")in body.");
            }
            return true;
        }
        return false;
    }

    private boolean subjectContainsCreateIssueSyntax(String subject)
    {
         Project p = EaseUtil.findProjectObjectInString(subject);
        if(p!=null)
        {
            if(log.isDebugEnabled())
            {
                log.debug("Found new issue syntax in subject.subject="+subject+". Project="+p.getKey());
            }
            return true;
        }
        return false;
    }

    private boolean subjectContainsCommentIssueSyntax(String subject)
    {
        Issue i = EaseUtil.findIssueObjectInString(subject);
        if(i!=null)
        {
            if(log.isDebugEnabled())
            {
                log.debug("Found issue key in subject. subject="+subject+". Issue key="+i.getKey());
            }
            return true;
        }
        return false;
    }

    private boolean bodyContainsCreateIssueSyntax(String body)
    {
        Map<String,String> fieldMap = EaseUtil.extractFields(body);
        if(fieldMap.containsKey(EaseUtil.EASE_FIELD_PROJECTKEY))
        {
            if(log.isDebugEnabled())
            {
                log.debug("Found new issue syntax ("+EaseUtil.EASE_FIELD_PROJECTKEY+"="+fieldMap.get(EaseUtil.EASE_FIELD_PROJECTKEY)+ ")in body.");
            }
            return true;
        }
        return false;
    }

    private boolean bodyContainsCommentIssueSyntax(String body)
    {
        Map<String,String> fieldMap = EaseUtil.extractFields(body);
        if(fieldMap.containsKey(EaseUtil.EASE_FIELD_ISSUEKEY))
        {
            if(log.isDebugEnabled())
            {
                log.debug("Found issuekey syntax ("+EaseUtil.EASE_FIELD_ISSUEKEY+"="+fieldMap.get(EaseUtil.EASE_FIELD_ISSUEKEY)+")in body.");
            }
            return true;
        }
        return false;
    }
}
