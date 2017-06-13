package com.easesolutions.jira.plugins.easemailhandler.handler;

import com.atlassian.annotations.PublicSpi;
import com.atlassian.core.util.collection.EasyList;
import com.atlassian.crowd.embedded.api.Group;
import com.atlassian.crowd.embedded.api.User;
import com.atlassian.crowd.embedded.impl.ImmutableUser;
import com.atlassian.crowd.util.SecureRandomStringUtils;
import com.atlassian.jira.JiraApplicationContext;
import com.atlassian.jira.bc.user.UserService;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.config.properties.APKeys;
import com.atlassian.jira.config.properties.ApplicationProperties;
import com.atlassian.jira.event.user.UserEventType;
import com.atlassian.jira.exception.ParseException;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.history.ChangeItemBean;
import com.atlassian.jira.mail.Email;
import com.atlassian.jira.mail.MailLoggingManager;
import com.atlassian.jira.security.PermissionManager;
import com.atlassian.jira.security.Permissions;
import com.atlassian.jira.service.util.handler.MessageHandler;
import com.atlassian.jira.service.util.handler.MessageHandlerContext;
import com.atlassian.jira.service.util.handler.MessageHandlerErrorCollector;
import com.atlassian.jira.service.util.handler.MessageHandlerExecutionMonitor;
import com.atlassian.jira.service.util.handler.MessageUserProcessor;
import com.atlassian.jira.user.UserKeyService;
import com.atlassian.jira.user.UserUtils;
import com.atlassian.jira.user.util.UserManager;
import com.atlassian.jira.util.I18nHelper;
import com.atlassian.jira.util.dbc.Assertions;
import com.atlassian.jira.web.util.FileNameCharacterCheckerUtil;
import com.atlassian.mail.MailUtils;
import com.easesolutions.jira.plugins.easemailhandler.util.ConfigUtil;
import com.google.common.collect.Lists;
import com.opensymphony.util.TextUtils;
import org.apache.commons.lang.ClassUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.ofbiz.core.entity.GenericEntityException;

import javax.annotation.Nullable;
import javax.mail.Address;
import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.internet.InternetAddress;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;

/**
 * An AbstractMessageHandler that stores the parameter map.
 * <p/>
 * TODO Insert summary about other responsibilities/ features of this class.
 * <p/>
 * <h3>Attachment processing</h3> This class does a number of things including the processing of parts that need to be
 * saved as attachments within the {@link #createAttachmentsForMessage(javax.mail.Message, com.atlassian.jira.issue.Issue, com.atlassian.jira.service.util.handler.MessageHandlerContext)} )} method.
 * <p/>
 * This method eventually calls others which attempt to locate all parts that are possible candidates and should be kept
 * after it is categorised a part as a particular type. These types are <ul> <li>Html - content type = text/html</li>
 * <li>PlainText - content type = text/plain</li> <li>Inline - any part that is not an 'external' attachment. Typically
 * this used to represent an inline image by most email clients</li> <li>Attachment - any part that is an 'external'
 * attachment that the user added to the email. These typically represent a binary or file that was attached and
 * accompanies the email.</li> </ul>
 * <p/>
 * A few protected methods within this class that are intended for overriding if behaviour needs to be customised. These
 * methods of interest are <ul> <li>{@link #attachAttachmentsParts(javax.mail.Part)}</li> <li>{@link
 * #attachHtmlParts(javax.mail.Part)} </li> <li>{@link #attachInlineParts(javax.mail.Part)} is abstract</li> <li>{@link
 * #attachMessagePart(javax.mail.Part, javax.mail.Message)} </li> <li>{@link #attachPlainTextParts(javax.mail.Part)} is
 * abstract</li> </ul>
 * <p/>
 * The two abstract attachXXX methods are implemented by sub classes.
 * <p/>
 * Numerous helper methods are available upon the {@link com.atlassian.mail.MailUtils MailUtils} class to assist with
 * simple tests.
 *
 * @deprecated traditionally this class has been used as a base class by custom handlers. However as of JIRA 5.0 we recommend
 *             not deriving from it and instead implementing directly MessageHandler and use composition instead and use such helper classes
 *             like {@link MailUtils} and {@link MessageUserProcessor}.
 */
@PublicSpi
@Deprecated
public abstract class AbstractMessageHandler implements MessageHandler
{
    protected final Logger log;
    /**
     * @Deprecated you should not store user names as they may change in JIRA 6.0 which supports rename user. Use {@link #KEY_REPORTER_KEY} and store user keys instead. Since v5.2.23
     */
    @Deprecated
    public static final String KEY_REPORTER = "reporterusername";
    public static final String KEY_REPORTER_KEY = "reporteruserkey";

    public static final String KEY_CATCHEMAIL = "catchemail";
    public static final String KEY_CREATEUSERS = "createusers";
    public static final String KEY_NOTIFYUSERS = "notifyusers";

    public static final String KEY_FINGER_PRINT = "fingerprint";

    /**
     * Value for parameter {@link #KEY_FINGER_PRINT} which matches legacy behaviour, accepting emails even if this JIRA
     * sent them.
     */
    public static final String VALUE_FINGER_PRINT_ACCEPT = "accept";

    /**
     * Default value for parameter {@link #KEY_FINGER_PRINT} which matches forwards emails if this JIRA sent them,
     * falling back to {@link #VALUE_FINGER_PRINT_IGNORE} if the forward email address is missing.
     */
    public static final String VALUE_FINGER_PRINT_FORWARD = "forward";

    /**
     * Value for parameter {@link #KEY_FINGER_PRINT} which makes this handler ignore emails detected to have been sent
     * by this instance of JIRA.
     */
    public static final String VALUE_FINGER_PRINT_IGNORE = "ignore";

    /**
     * Valid values for {@link #KEY_FINGER_PRINT}
     */
    private final List VALUES_FINGERPRINT = EasyList.build(VALUE_FINGER_PRINT_ACCEPT, VALUE_FINGER_PRINT_FORWARD, VALUE_FINGER_PRINT_IGNORE);

    public static final String KEY_BULK = "bulk";
    //the 4 possible values of bulk key. (default in other cases)
    public static final String VALUE_BULK_ACCEPT = "accept";
    public static final String VALUE_BULK_IGNORE = "ignore";
    public static final String VALUE_BULK_FORWARD = "forward";
    public static final String VALUE_BULK_DELETE = "delete";

    protected static final String CONTENT_TYPE_TEXT = "text/plain";

    protected static final String HEADER_MESSAGE_ID = "message-id";
    protected static final String HEADER_IN_REPLY_TO = "in-reply-to";

    /**
     * filename used if one cannot be determined from an attached message
     */
    private static final String ATTACHED_MESSAGE_FILENAME = "attachedmessage";

    /**
     * The default filename assigned to attachments that do not contain a filename etc
     */
    private final static String DEFAULT_BINARY_FILE_NAME = "binary.bin";

    /**
     * This is a silly protected field that indicates whether the email should be deleted BUT only after the
     * canHandleMessage() method is called.  It is used as a mechanism to indicate two values from from a single method
     * call.
     * <p/>
     * One say it should be lined up against the wall and shot, with a 2 value object returned instead.  But in the
     * interests of binary compatibility its left as is for the moment.
     */
    protected boolean deleteEmail;

    protected Map<String, String> params = new HashMap<String, String>();

    /**
     * Username of default reporter, if sender not recognized.
     */
    public String reporteruserName;

    /**
     * Userkey of default reporter, if sender not recognized.
     */
    public String reporterUserKey;
    /**
     * New issues without this recipient are ignored.
     */
    public String catchEmail;

    /**
     * How to handle emails with header: "Precedence: bulk"
     */
    public String bulk;

    /**
     * Whether to create users if they do not exist
     */
    public boolean createUsers;

    public static final String KEY_NEW_USER_GROUP_NAME = "newUserGroupName";
    public String newUserGroupName;

    public static final String KEY_MAILBOX_EMAIL = "mailBoxEmail";
    public String mailBoxEmail; // to and cc field should exclude the incoming mailbox address

    public boolean notifyUsers;

    /**
     * Policy for handling email that has JIRA's fingerprint on it. The default configuration is "forward" which
     * indicates that the forward address should be sent the email instead. If there is no foward email address
     * configured or if the fingerPrintPolicy parameter is set to "ignore", the message will not be picked up. A value
     * of "accept" makes JIRA vulnerable to certain types of mail loops. JRA-12467
     */
    private String fingerPrintPolicy;

    final protected UserManager userManager;
    final protected MessageUserProcessor messageUserProcessor;
    final protected ApplicationProperties applicationProperties;
    final protected PermissionManager permissionManager;
    private final JiraApplicationContext jiraApplicationContext;
    private final UserKeyService userKeyService;
    private static final FileNameCharacterCheckerUtil fileNameCharacterCheckerUtil = new FileNameCharacterCheckerUtil();
    private static final char INVALID_CHAR_REPLACEMENT = '_';

    protected ConfigUtil configUtil;

    /**
     * @deprecated use instead {@link #AbstractMessageHandler(com.atlassian.jira.user.util.UserManager,
     *             com.atlassian.jira.config.properties.ApplicationProperties, com.atlassian.jira.JiraApplicationContext,
     *             com.atlassian.jira.mail.MailLoggingManager, MessageUserProcessor, PermissionManager)}. We should promote dependency injection everywhere we
     *             can.
     */
    @Deprecated
    protected AbstractMessageHandler()
    {
        this(ComponentAccessor.getUserManager(),
                ComponentAccessor.getApplicationProperties(), ComponentAccessor.getComponent(JiraApplicationContext.class),
                ComponentAccessor.getComponent(MailLoggingManager.class), ComponentAccessor.getComponent(MessageUserProcessor.class));
    }

    /**
     * Deprecated Constructor
     *
     * @deprecated Use {@link #AbstractMessageHandler(UserManager, ApplicationProperties,
     *             JiraApplicationContext, MailLoggingManager, MessageUserProcessor, PermissionManager)} instead. Since v5.1.
     */
    protected AbstractMessageHandler(final ApplicationProperties applicationProperties, final JiraApplicationContext jiraApplicationContext)
    {
        this(ComponentAccessor.getUserManager(), applicationProperties,
                jiraApplicationContext, ComponentAccessor.getComponent(MailLoggingManager.class),
                ComponentAccessor.getComponent(MessageUserProcessor.class));
    }

    /**
     * Deprecated Constructor
     *
     * @deprecated Use {@link #AbstractMessageHandler(UserManager, ApplicationProperties,
     *             JiraApplicationContext, MailLoggingManager, MessageUserProcessor, PermissionManager)} instead. Since v5.1.
     */
    protected AbstractMessageHandler(UserManager userManager,
                                     ApplicationProperties applicationProperties, JiraApplicationContext jiraApplicationContext,
                                     MailLoggingManager mailLoggingManager, MessageUserProcessor messageUserProcessor)
    {
        this(userManager, applicationProperties, jiraApplicationContext, mailLoggingManager, messageUserProcessor, ComponentAccessor.getPermissionManager(), ComponentAccessor.getUserKeyService());
    }

    protected AbstractMessageHandler(UserManager userManager,
                                     ApplicationProperties applicationProperties, JiraApplicationContext jiraApplicationContext,
                                     MailLoggingManager mailLoggingManager, MessageUserProcessor messageUserProcessor, PermissionManager permissionManager)
    {
        this(userManager, applicationProperties, jiraApplicationContext, mailLoggingManager, messageUserProcessor, ComponentAccessor.getPermissionManager(), ComponentAccessor.getUserKeyService());
    }

    protected AbstractMessageHandler(UserManager userManager,
                                     ApplicationProperties applicationProperties, JiraApplicationContext jiraApplicationContext,
                                     MailLoggingManager mailLoggingManager, MessageUserProcessor messageUserProcessor, PermissionManager permissionManager, UserKeyService userKeyService)
    {
        this.userManager = userManager;
        this.applicationProperties = applicationProperties;
        this.jiraApplicationContext = jiraApplicationContext;
        this.userKeyService = userKeyService;
        log = mailLoggingManager.getIncomingMailChildLogger(ClassUtils.getShortClassName(getClass()));
        com.atlassian.plugin.util.Assertions.notNull("messageUserProcessor", messageUserProcessor);
        this.messageUserProcessor = messageUserProcessor;
        this.permissionManager = permissionManager;

    }

    public void init(final Map<String, String> params, MessageHandlerErrorCollector errorCollector)
    {
        this.params = params;

        if (params.containsKey(KEY_REPORTER))
        {
            reporteruserName = params.get(KEY_REPORTER);
            reporterUserKey = userKeyService.getKeyForUsername(reporteruserName);
        }

        if (params.containsKey(KEY_REPORTER_KEY))
        {
            reporterUserKey = params.get(KEY_REPORTER_KEY);
            reporteruserName = userKeyService.getUsernameForKey(reporterUserKey);
        }
        if (params.containsKey(KEY_CATCHEMAIL))
        {
            catchEmail = params.get(KEY_CATCHEMAIL);
        }

        if (params.containsKey(KEY_BULK))
        {
            bulk = params.get(KEY_BULK);
        }

        if (params.containsKey(KEY_CREATEUSERS))
        {
            createUsers = Boolean.valueOf(params.get(KEY_CREATEUSERS));

            if (createUsers)
            {
                // Check that the default reporter is NOT configured
                // As if it is configured and createing users is set to true,
                // it is ambiguous whether to create a new user or use the default reporter
                if (reporteruserName != null)
                {
                    if (userManager.hasWritableDirectory())
                    {
                        errorCollector.warning("Default Reporter Username set to '" + reporteruserName + "' and " + KEY_CREATEUSERS + " is set to true.");
                        errorCollector.warning("Ignoring the Default Reporter Username, users will be created if they do not exist.");
                    } else
                    {
                        errorCollector.warning("Default Reporter Username set to '" + reporteruserName + "', " + KEY_CREATEUSERS + " is set to true and no user directories are writable.");
                        errorCollector.warning("Ignoring the " + KEY_CREATEUSERS + " flag. Using the default Reporter username '" + reporteruserName + "'.");
                    }
                } else if (!userManager.hasWritableDirectory())
                {
                    errorCollector.warning(KEY_CREATEUSERS + " is set to true, but no user directories are writable.  Users will NOT be created.");
                }

            }

            //JRA-13996: Don't use Boolean.getBoolean(String), it actually looks up to see if a system property of the passed name is
            // set to true.
            notifyUsers = !params.containsKey(KEY_NOTIFYUSERS) || Boolean.parseBoolean(params.get(KEY_NOTIFYUSERS));
        } else
        {
            log.debug("Defaulting to not creating users");
            createUsers = false;
            log.debug("Defaulting to notifying users since user creation is not specified");
            notifyUsers = true;
        }

        if (params.containsKey(KEY_FINGER_PRINT) && VALUES_FINGERPRINT.contains(params.get(KEY_FINGER_PRINT)))
        {
            fingerPrintPolicy = params.get(KEY_FINGER_PRINT);
        } else
        {
            log.debug("Defaulting to fingerprint policy of 'forward'");
            fingerPrintPolicy = VALUE_FINGER_PRINT_FORWARD;
        }

        if(params.containsKey(KEY_NEW_USER_GROUP_NAME))
        {
            newUserGroupName = params.get(KEY_NEW_USER_GROUP_NAME);
        }

        if(params.containsKey(KEY_MAILBOX_EMAIL))
        {
            mailBoxEmail = params.get(KEY_MAILBOX_EMAIL);
        }

        configUtil = new ConfigUtil();
    }

    /**
     * Perform the specific work of this handler for the given message.
     *
     * @return true if the message is to be deleted from the source.
     * @throws MessagingException if anything went wrong.
     */
    @Override
    public abstract boolean handleMessage(Message message, MessageHandlerContext context)
            throws MessagingException;

    /**
     * Validation call to be made at the start of handleMessage().<br> It sets a global boolean deleteEmail, whether the
     * email should be deleted if it cannot be handled. ie. return deleteEmail if canHandleMessage() is false
     *
     * @param message message to check if it can be handled
     * @return whether the message should be handled
     */
    protected boolean canHandleMessage(final Message message, MessageHandlerExecutionMonitor monitor)
    {
        /**
         * JRA-15582 - The default handler behaviour is to NOT delete the email.  Each bit of code that
         * wants to delete email must explicitly set this flag to true.
         */
        deleteEmail = false;

        /**
         * If the message fails a finder print check, then we don't want to handle it.
         */
        if (!fingerPrintCheck(message, monitor))
        {
            monitor.messageRejected(message, "Rejecting message due to failed fingerprint check.");
            return false;
        }

        if (checkBulk(message, monitor))
        {
            monitor.messageRejected(message, "Rejecting message due to failed bulk check.");
            return false;
        }

        // if the recipient is specified, check it is present in the message and reject if not
        if (catchEmail != null)
        {
            //JRA-16176: If  a message's recipients cannot be parsed, then we assume that the message is invalid. This
            // will leave the message in the mail box if there is no forward address, otherwise it will forward the
            // message to the forward address.

            final boolean forCatchAll;
            try
            {
                forCatchAll = MailUtils.hasRecipient(catchEmail, message);
            } catch (final MessagingException exception)
            {
                deleteEmail = false;
                log.debug("Could not parse message recipients. Assuming message is bad.", exception);
                final String text = getI18nBean().getText("admin.errors.bad.destination.address");
                monitor.error(text, exception);
                monitor.messageRejected(message, text);
                return false;
            }

            if (!forCatchAll)
            {
                //
                // JRA-15580 - We should NEVER delete the email if its not intended for this "catchemail"
                //
                deleteEmail = false;
                monitor.messageRejected(message, "The messages recipient(s) does not match catch mail list.");
                logCantHandleRecipients(message, monitor);
                return false;
            } else
            {
                // its a hit on recipient address, we want to delete this email when we are done
                deleteEmail = true;
            }
        }

        return true;
    }

    private boolean checkBulk(final Message message, MessageHandlerExecutionMonitor messageHandlerExecutionMonitor)
    {
        try
        {
            if ("bulk".equalsIgnoreCase(getPrecedenceHeader(message)) || isDeliveryStatus(message) || isAutoSubmitted(message))
            {
                //default action is to process the email for backwards compatibility
                if (bulk != null)
                {
                    if (VALUE_BULK_IGNORE.equalsIgnoreCase(bulk))
                    {
                        log.debug("Ignoring email with bulk delivery type");
                        deleteEmail = false;
                        return true;
                    } else if (VALUE_BULK_FORWARD.equalsIgnoreCase(bulk))
                    {
                        log.debug("Forwarding email with bulk delivery type");
                        messageHandlerExecutionMonitor.error(getI18nBean().getText("admin.forward.bulk.mail"));
                        deleteEmail = false;
                        return true;
                    } else if (VALUE_BULK_DELETE.equalsIgnoreCase(bulk))
                    {
                        log.debug("Deleting email with bulk delivery type");
                        deleteEmail = true;
                        return true;
                    }
                }
            }
            return false;
        } catch (final MessagingException mex)
        {
            if (log.isDebugEnabled())
            {
                log.debug("Error occured while looking for bulk headers - assuming not bulk email: " + mex.getMessage(), mex);
            }
            return false;
        }
    }

    /**
     * Determines if the given message is acceptable for handling based on the presence of any JIRA fingerprint headers
     * and this {@link com.atlassian.jira.service.util.handler.MessageHandler}'s configuration.
     *
     * @param message the message to check.
     * @return false only if this handler should not handle the message because of a JIRA fingerprint.
     */
    boolean fingerPrintCheck(final Message message, MessageHandlerExecutionMonitor messageHandlerExecutionMonitor)
    {
        boolean fingerPrintClean = true; // until proven guilty
        final List fingerPrintHeaders = getFingerPrintHeader(message);
        final String instanceFingerPrint = jiraApplicationContext.getFingerPrint();
        if (!fingerPrintHeaders.isEmpty())
        {
            if (log.isDebugEnabled())
            {
                log.debug("JIRA fingerprints found on on incoming email message: ");
                for (final Object fingerPrintHeader : fingerPrintHeaders)
                {
                    log.debug("fingerprint: " + fingerPrintHeader);
                }
            }
            if (fingerPrintHeaders.contains(instanceFingerPrint))
            {
                log.warn("Received message carrying this JIRA instance fingerprint (" + instanceFingerPrint + ")");
                if (VALUE_FINGER_PRINT_ACCEPT.equalsIgnoreCase(fingerPrintPolicy))
                {
                    log.warn("Handler is configured to accept such messages. Beware of mail loops: JRA-12467");
                } else if (VALUE_FINGER_PRINT_FORWARD.equalsIgnoreCase(fingerPrintPolicy))
                {
                    log.debug("Forwarding fingerprinted email.");
                    messageHandlerExecutionMonitor.error(getI18nBean().getText("admin.forward.mail.loop"));
                    fingerPrintClean = false;
                } else if (VALUE_FINGER_PRINT_IGNORE.equalsIgnoreCase(fingerPrintPolicy))
                {
                    log.info("Handler is configured to ignore this message.");
                    fingerPrintClean = false;
                }

            } else
            {
                log.info("Received message with another JIRA instance's fingerprint");
            }
        }
        return fingerPrintClean;
    }

    /**
     * Returns the values of the JIRA fingerprint headers on the message, or null if there is no such header. Messages
     * sent by v3.13 of JIRA and later should all carry the fingerprint header with the value being the instance's
     * "unique" fingerprint.
     *
     * @param message the message to get the header from.
     * @return the possibly empty list of values of the JIRA fingerprint headers of the sending instance.
     * @since v3.13
     */
    List<String> getFingerPrintHeader(final Message message)
    {
        List<String> headers = Collections.emptyList();
        try
        {
            final String[] headerArray = message.getHeader(Email.HEADER_JIRA_FINGER_PRINT);
            if (headerArray != null)
            {
                headers = Arrays.asList(headerArray);
            }
        } catch (final MessagingException e)
        {
            log.error("Failed to get mail header " + Email.HEADER_JIRA_FINGER_PRINT);
        }
        return headers;
    }

    /**
     * Loops through all the {@link Part}s, and for each one of type {@link Part#ATTACHMENT}, call {@link
     * #createAttachmentWithPart(javax.mail.Part, com.atlassian.crowd.embedded.api.User, Issue, MessageHandlerContext)}.
     *
     * @param message The multipart message to search for attachments in
     * @param issue   The issue to create attachments in
     * @return a collection of change items, one for each attachment added. If no attachments are added, returns and
     *         empty collection.
     * @throws IOException        If there is a problem creating the attachment
     * @throws MessagingException If there is a problem reading the message
     */
    protected Collection<ChangeItemBean> createAttachmentsForMessage(final Message message, final Issue issue,
                                                                     final MessageHandlerContext context) throws IOException, MessagingException
    {
        final Collection<ChangeItemBean> attachmentChangeItems = Lists.newArrayList();
        final AttachmentHandler attachmentHandler;

        final User reporter = getReporter(message, context);
        final boolean attachPermission = permissionManager.hasPermission(Permissions.CREATE_ATTACHMENT, issue, reporter);
        final boolean attachmentsAllowed = applicationProperties.getOption(APKeys.JIRA_OPTION_ALLOWATTACHMENTS);
        if (attachmentsAllowed && attachPermission)
        {
            attachmentHandler = new AttachmentHandler()
            {
                @Override
                public void handlePart(Part part, Message containingMessage) throws IOException, MessagingException
                {
                    if (shouldAttach(part, containingMessage))
                    {
                        final ChangeItemBean changeItemBean = createAttachmentWithPart(part, reporter, issue, context);
                        if (changeItemBean != null)
                        {
                            attachmentChangeItems.add(changeItemBean);
                        }
                    }
                }

                @Override
                public void summarize()
                {
                }
            };
        } else
        {
            attachmentHandler = new AttachmentHandler()
            {
                private final List<String> skippedFiles = Lists.newArrayList();

                @Override
                public void handlePart(Part part, Message containingMessage) throws IOException, MessagingException
                {
                    if (shouldAttach(part, containingMessage))
                    {
                        final String filenameForAttachment = getFilenameForAttachment(part);
                        if (StringUtils.isNotBlank(filenameForAttachment))
                        {
                            // if filename is blank the file would not be attached anyway - spaghetti FTW
                            skippedFiles.add(filenameForAttachment);
                        }
                    }
                }

                @Override
                public void summarize()
                {
                    if (!skippedFiles.isEmpty())
                    {
                        final String message = !attachmentsAllowed
                                ? getI18nBean().getText("jmp.handler.attachments.disabled")
                                : getI18nBean().getText("jmp.handler.attachments.no.create.permission", reporter.getName(), issue.getProjectObject().getKey());
                        if (log.isDebugEnabled())
                        {
                            log.debug(message + StringUtils.join(skippedFiles, ", "));
                        }
                        final String comment = message + "\n - " + StringUtils.join(skippedFiles, "\n - ");
                        context.createComment(issue, reporter, comment, false);
                    }
                }
            };
        }

        final Object messageContent = message.getContent();
        if (messageContent instanceof Multipart)
        {
            handleMultipart((Multipart) messageContent, message, attachmentHandler);
        }
        //JRA-12123: Message is not a multipart, but it has a disposition of attachment.  This means that
        //we got a message with an empty body and an attachment.  We'll ignore inline.
        else if (Part.ATTACHMENT.equalsIgnoreCase(message.getDisposition()))
        {
            log.debug("Trying to add attachment to issue from attachment only message.");

            attachmentHandler.handlePart(message, null);
        }

        attachmentHandler.summarize();
        return attachmentChangeItems;
    }

    private void handleMultipart(final Multipart multipart, Message message, AttachmentHandler attachmentHandler) throws MessagingException, IOException
    {
        for (int i = 0, n = multipart.getCount(); i < n; i++)
        {
            if (log.isDebugEnabled())
            {
                log.debug(String.format("Adding attachments for multi-part message. Part %d of %d.", i + 1, n));
            }

            final BodyPart part = multipart.getBodyPart(i);

            // there may be non-attachment parts (e.g. HTML email) - fixes JRA-1842
            final Object partContent = part.getContent();
            if (partContent instanceof Multipart)
            {
                // Found another multi-part - process it and collection all change items.
                handleMultipart((Multipart) partContent, message, attachmentHandler);
            } else
            {
                // JRA-15133: if this part is an attached message, skip it if:
                // * the option to ignore attached messages is set to true, OR
                // * this message is in reply to the attached one (redundant info)
                // Note: this is now covered by the shouldAttach() method
                attachmentHandler.handlePart(part, message);
            }
        }
    }

    /**
     * Checks if the containing message is "In-Reply-To" to the attached message. This is done through checking standard
     * email headers as specified in RFC-822.
     *
     * @param containingMessage a message, which may be the reply.
     * @param attachedMessage   another message which may be the original
     * @return true if the first message is in reply to the second.
     * @throws ParseException                if there was an error in retrieving the required message headers
     * @throws javax.mail.MessagingException if javamail complains
     */
    private boolean isMessageInReplyToAnother(final Message containingMessage, final Message attachedMessage)
            throws MessagingException, ParseException
    {
        // Note: we are using the fact that most common mail clients use In-Reply-To to reference the Message-ID of the
        // message being replied to. This is "defacto standard" but is not guaranteed by the spec.
        final String attachMessageId = getMessageId(attachedMessage);
        final String[] replyToIds = containingMessage.getHeader(HEADER_IN_REPLY_TO);

        if (log.isDebugEnabled())
        {
            log.debug("Checking if attachment was reply to containing message:");
            log.debug("\tAttachment mesage id: " + attachMessageId);
            log.debug("\tNew message reply to values: " + Arrays.toString(replyToIds));
        }

        if (replyToIds != null)
        {
            for (final String id : replyToIds)
            {
                if ((id != null) && id.equalsIgnoreCase(attachMessageId))
                {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns the Message-ID for a given message.
     *
     * @param message a message
     * @return the Message-ID
     * @throws ParseException                if the Message-ID header was not present
     * @throws javax.mail.MessagingException if javamail complains
     */
    String getMessageId(final Message message) throws MessagingException, ParseException
    {
        // Note: we get an array because Message-ID is an arbitrary header, but really there can be only one Message-ID
        // value (if it is present)
        final String[] originalMessageIds = message.getHeader(HEADER_MESSAGE_ID);
        if ((originalMessageIds == null) || (originalMessageIds.length == 0))
        {
            final String msg = "Could not retrieve Message-ID header from message: " + message;
            log.debug(msg);
            throw new ParseException(msg);
        }
        return originalMessageIds[0];
    }

    /**
     * This method determines if a particular part should be included added as an attachment to an issue.
     *
     * @param part              the part to potentially attach
     * @param containingMessage the message which contained the part - may be null
     * @return true if the part should be attached; false otherwise
     * @throws java.io.IOException           if javamail complains
     * @throws javax.mail.MessagingException if javamail complains
     */
    final protected boolean shouldAttach(final Part part, final Message containingMessage)
            throws MessagingException, IOException
    {
        Assertions.notNull("part", part);

        boolean attach;

        if (log.isDebugEnabled())
        {
            log.debug("Checking if attachment should be added to issue:");
            log.debug("\tContent-Type: " + part.getContentType());
            log.debug("\tContent-Disposition: " + part.getDisposition());
        }

        if (MailUtils.isPartMessageType(part) && (null != containingMessage))
        {
            log.debug("Attachment detected as a rfc/822 message.");
            attach = attachMessagePart(part, containingMessage);
        } else if (isPartAttachment(part))
        {
            log.debug("Attachment detected as an \'Attachment\'.");
            attach = attachAttachmentsParts(part);
        } else if (isPartInline(part))
        {
            log.debug("Attachment detected as an inline element.");
            attach = attachInlineParts(part);
        } else if (MailUtils.isPartPlainText(part))
        {
            log.debug("Attachment detected as plain text.");
            attach = attachPlainTextParts(part);
        } else if (MailUtils.isPartHtml(part))
        {
            log.debug("Attachment detected as HTML.");
            attach = attachHtmlParts(part);
        } else if (MailUtils.isPartRelated(containingMessage))
        {
            log.debug("Attachment detected as related content.");
            attach = attachRelatedPart(part);
        } else
        {
            attach = false;
        }

        if (log.isDebugEnabled())
        {
            if (attach)
            {
                log.debug("Attachment was added to issue");
            } else
            {
                log.debug("Attachment was ignored.");
            }
        }

        return attach;
    }

    private boolean isPartAttachment(Part part) throws MessagingException
    {
        return MailUtils.isPartAttachment(part)
                || (StringUtils.isNotBlank(part.getFileName()) && StringUtils.isNotBlank(part.getContentType()) && part.getSize() > 0);
    }

    /**
     * This method determines whether or not plain text parts should be attached.
     *
     * @param part the part to be attached - already determined to be type text/plain.
     * @return true if the part should be attached; false otherwise
     * @throws java.io.IOException           if javamail complains
     * @throws javax.mail.MessagingException if javamail complains
     */
    abstract protected boolean attachPlainTextParts(final Part part) throws MessagingException, IOException;

    /**
     * This method determines whether or not HTML parts should be attached.
     *
     * @param part the part to be attached - already determined to be type text/html.
     * @return true if the part should be attached; false otherwise
     * @throws java.io.IOException           if javamail complains
     * @throws javax.mail.MessagingException if javamail complains
     */
    abstract protected boolean attachHtmlParts(final Part part) throws MessagingException, IOException;

    /**
     * Only attach an inline part if it's content is not empty and if it is not a signature part.
     *
     * @param part a mail part - assumed to have inline disposition
     * @return whether or not this inline part should be attached.
     * @throws MessagingException if Content-Type checks fail
     * @throws IOException        if content checks fail
     */
    protected boolean attachInlineParts(final Part part) throws MessagingException, IOException
    {
        return !MailUtils.isContentEmpty(part) && !MailUtils.isPartSignaturePKCS7(part);
    }

    /**
     * Only attach an attachment part if it's content is not empty and if it is not a signature part.
     *
     * @param part a mail part - assumed to have attachment disposition
     * @return whether or not this inline part should be attached.
     * @throws MessagingException if Content-Type checks fail
     * @throws IOException        if content checks fail
     */
    protected boolean attachAttachmentsParts(final Part part) throws MessagingException, IOException
    {
        return !MailUtils.isContentEmpty(part) && !MailUtils.isPartSignaturePKCS7(part);
    }

    /**
     * JRA-15133: if this part is an attached message, skip it if: <ul> <li>the option to ignore attached messages is
     * set to true, OR</li> <li>this message is in reply to the attached one (redundant info), OR</li> <li>if the
     * message is not in reply to the attached one, skip if the content is empty</li> </ul>
     * <p/>
     * This is required to handle the behaviour of some mail clients (e.g. Outlook) who, when replying to a message,
     * include the entire original message as an attachment with content type "message/rfc822". In these cases, the
     * attached message is redundant information, so we skip it.
     *
     * @param messagePart       the Message part (already known to be of message content type)
     * @param containingMessage the original message which contains messagePart
     * @return true if the part should be attached, false otherwise
     * @throws java.io.IOException           if javamail complains
     * @throws javax.mail.MessagingException if javamail complains
     */
    protected boolean attachMessagePart(final Part messagePart, final Message containingMessage)
            throws IOException, MessagingException
    {
        boolean keep = false;

        // only keep message parts if we are not ignoring them
        if (!shouldIgnoreEmailMessageAttachments())
        {
            // .. and the message part is not being replied to by this message
            if (!isReplyMessagePart(messagePart, containingMessage))
            {
                // .. and the message part is not empty
                keep = !MailUtils.isContentEmpty(messagePart);

                if (!keep && log.isDebugEnabled())
                {
                    log.debug("Attachment not attached to issue: Message is empty.");
                }
            } else
            {
                log.debug("Attachment not attached to issue: Detected as reply.");
            }
        } else
        {
            log.debug("Attachment not attached to issue: Message attachment has been disabled.");
        }

        return keep;
    }

    /**
     * JRA-15670: if this part is contained within a multipart/related message, keep it, as it may be a legitimate
     * attachment, but without the content disposition set to a sensible value (e.g. when using Outlook 2007 and Rich
     * Text format).
     *
     * @param part the part contained within the related message
     * @return true if the part should be attached, false otherwise
     * @throws java.io.IOException           if javamail complains
     * @throws javax.mail.MessagingException if javamail complains
     */
    protected boolean attachRelatedPart(final Part part) throws IOException, MessagingException
    {
        return !MailUtils.isContentEmpty(part);
    }

    /**
     * Tests if jira has been configured to ignore message attachments.
     *
     * @return Returns true if email message attachments should be ignored, otherwise returns false.
     */
    boolean shouldIgnoreEmailMessageAttachments()
    {
        return applicationProperties.getOption(APKeys.JIRA_OPTION_IGNORE_EMAIL_MESSAGE_ATTACHMENTS);
    }

    /**
     * Helper which tests if the incoming part is a reply to the containing message
     *
     * @param messagePart       The part being tested
     * @param containingMessage The container message
     * @return True if the part is definitely a reply to the message, otherwise returns false;
     * @throws java.io.IOException           if javamail complains
     * @throws javax.mail.MessagingException if javamail complains
     */
    private boolean isReplyMessagePart(final Part messagePart, final Message containingMessage)
            throws IOException, MessagingException
    {
        boolean replyMessage;

        try
        {
            replyMessage = isMessageInReplyToAnother(containingMessage, (Message) messagePart.getContent());
        } catch (final ParseException e)
        {
            log.debug("Can't tell if the message is in reply to the attached message -- will attach it in case");
            replyMessage = false;
        }

        return replyMessage;
    }

    /**
     * Create an attachment for a particular mime-part.  The BodyPart must be of type {@link Part#ATTACHMENT}.
     *
     * @param part     part of disposition {@link javax.mail.Part#ATTACHMENT} to create the attachment from
     * @param reporter issue reporter
     * @param issue    issue to create attachments in
     * @return A {@link ChangeItemBean} representing the added attachment, or null if no attachment was created
     * @throws IOException If there is a problem creating the attachment in the filesystem
     */
    protected ChangeItemBean createAttachmentWithPart(final Part part, final User reporter, final Issue issue,
                                                      MessageHandlerContext context) throws IOException
    {
        try
        {
            final String contentType = MailUtils.getContentType(part);
            final String rawFilename = part.getFileName();
            String filename = getFilenameForAttachment(part);

            final File file = getFileFromPart(part, (issue != null ? issue.getKey() : "null"));

            if (log.isDebugEnabled())
            {
                log.debug("part=" + part);
                log.debug("Filename=" + filename + ", content type=" + contentType + ", content=" + part.getContent());
            }

            filename = renameFileIfInvalid(filename, issue, reporter, context);

            final ChangeItemBean cib = context.createAttachment(file, filename, contentType, reporter, issue);
            if (cib != null)
            {
                log.debug("Created attachment " + rawFilename + " for issue " + issue.getKey());
                return cib;
            } else
            {
                log.debug("Encountered an error creating the attachment " + rawFilename + " for issue " + issue.getKey());
                return null;
            }
        } catch (final Exception e)
        {
            log.error("Exception while creating attachment for issue " + (issue != null ? issue.getKey() : "null") + ": " + e, e);
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * Handy method which takes a number of strategies when attempting to give a filename for a particular part. The
     * filename may already be present in the part or may need to be formulated or composed from other identifies within
     * the part (such as a subject, content type etc).
     *
     * @param part The part being tested.
     * @return The filename for the attachment or null if one was not present.
     * @throws MessagingException relays any MessagingException thrown by a lower layer such as java mail
     * @throws IOException        relays any IOExceptions
     */
    protected String getFilenameForAttachment(final Part part) throws MessagingException, IOException
    {
        String filename = getFilenameFromPart(part);
        if (null == filename)
        {
            if (MailUtils.isPartMessageType(part))
            {
                filename = getFilenameFromMessageSubject(part);
            } else if (MailUtils.isPartInline(part))
            {
                filename = getFilenameFromContentType(part);
            }
        }

        // double check that filename extracting worked!
        if (null != filename)
        {
            if (StringUtils.isBlank(filename))
            {
                final String message = "Having found a filename(aka filename is not null) filename should not be an empty string, but is...";
                log.warn(message);

                // since empty string is invalid, return null and let a name be default or generated name be used instead.
                filename = null;
            }
        }

        return filename;
    }

    /**
     * Retrieves the filename from a mail part and MIME decodes it if necessary.
     *
     * @param part a mail part - may or may not have a file name.
     * @return the file name set on the part, or null.
     * @throws MessagingException if retrieving the file name fails.
     * @throws IOException        if doing the MIME decoding fails.
     */
    private String getFilenameFromPart(final Part part) throws MessagingException, IOException
    {
        String filename = part.getFileName();
        if (null != filename)
        {
            filename = MailUtils.fixMimeEncodedFilename(filename);
        }
        return filename;
    }

    private String getFilenameFromMessageSubject(final Part part) throws MessagingException, IOException
    {
        // JRA-15133: determine filename from subject line of the message
        final Message message = (Message) part.getContent();
        String filename = message.getSubject();
        if (StringUtils.isBlank(filename))
        {
            // if no subject, use Message-ID
            try
            {
                filename = getMessageId(message);
            } catch (final ParseException e)
            {
                // no Message-ID, use constant
                filename = ATTACHED_MESSAGE_FILENAME;
            }
        }

        return filename;
    }

    /**
     * Allocates or composes a filename from a part, typically this is done by massaging the content type into a
     * filename.
     *
     * @param part The part
     * @return The composed filename
     * @throws MessagingException May be thrown by javamail
     * @throws IOException        May be thrown by javamail.
     */
    private String getFilenameFromContentType(final Part part) throws MessagingException, IOException
    {
        String filename = DEFAULT_BINARY_FILE_NAME;

        final String contentType = MailUtils.getContentType(part);
        final int slash = contentType.indexOf("/");
        if (-1 != slash)
        {
            final String subMimeType = contentType.substring(slash + 1);

            // if its not a binary attachment convert the content type into a filename image/gif becomes image-file.gif
            if (!subMimeType.equals("bin"))
            {
                filename = contentType.substring(0, slash) + '.' + subMimeType;
            }
        }

        return filename;
    }

    /**
     * Replaces all invalid characters in the filename using {@link FileNameCharacterCheckerUtil#replaceInvalidChars(String,
     * char)} with {@link #INVALID_CHAR_REPLACEMENT} as the replacement character.
     *
     * @param filename filename to check if its valid
     * @param issue    issue the file is to be attached
     * @param reporter the author of the comment to add to the issue if the filename is invalid
     * @return <li>if filename is null, returns null</li> <li>if its valid, returns filename</li> <li>if its invalid,
     *         returns filename with all invalid characters replaced with {@link #INVALID_CHAR_REPLACEMENT}</li>
     */
    protected String renameFileIfInvalid(final String filename, final Issue issue, final User reporter,
                                         MessageHandlerContext context)
    {
        if (filename == null)
        {
            //let the attachmentManager handle the null filename when creating.
            return null;
        }

        //replace any invalid characters with the INVALID_CHAR_REPLACEMENT character
        final String replacedFilename = fileNameCharacterCheckerUtil.replaceInvalidChars(filename, INVALID_CHAR_REPLACEMENT);
        //if the filename has changed then add a comment to the issue to say it has been changed because of invalid characters
        if (!filename.equals(replacedFilename))
        {
            if (log.isDebugEnabled())
            {
                log.debug("Filename was invalid: replacing '" + filename + "' with '" + replacedFilename + "'");
            }
            final String body = getI18nBean().getText(
                    "admin.renamed.file.cause.of.invalid.chars", filename, replacedFilename);
            context.createComment(issue, reporter, body, false);
            return replacedFilename;
        }
        return filename;
    }

    protected File getFileFromPart(final Part part, final String issueKey)
            throws IOException, MessagingException, GenericEntityException
    {
        File tempFile = null;
        try
        {
            tempFile = File.createTempFile("tempattach", "dat");
            final FileOutputStream out = new FileOutputStream(tempFile);

            try
            {
                part.getDataHandler().writeTo(out);
            } finally
            {
                out.close();
            }
        } catch (final IOException e)
        {
            log.error("Problem reading attachment from email for issue " + issueKey, e);
        }
        if (tempFile == null)
        {
            throw new IOException("Unable to create file?");
        }
        return tempFile;
    }

    /**
     * Get the reporter from the email address who sent the message, or else create a new  user if creating users is set
     * to true, or use the default reporter if one is specified.
     * <p/>
     * If neither of these are found, return null.
     *
     * @param message The email message to search through.
     * @return The user who sent the email, or the default reporter, or null.
     * @throws MessagingException If there is a problem getting the user who created the message.
     */
    protected User getReporter(final Message message, MessageHandlerContext context) throws MessagingException
    {
        User reporter = getMessageUserProcessor().getAuthorFromSender(message);

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

    protected MessageUserProcessor getMessageUserProcessor()
    {
        return messageUserProcessor;
    }

    /**
     * Tries to create a user using the details provided by the reporter.  Fails if external user managment is turned on
     * or, if no valid from email address was specified.
     *
     * @param message The original e-mail message.
     * @param context execution context
     * @return A new user or null.
     */
    @Nullable
    protected User createUserForReporter(final Message message, MessageHandlerContext context)
    {
        User reporter = null;
        try
        {
            if (!userManager.hasWritableDirectory())
            {
                context.getMonitor().warning("Unable to create user for reporter because no user directories are writable.");
                return null;
            }

            // If reporter is not a recognised user, then create one from the information in the e-mail
            log.debug("Cannot find reporter for message. Creating new user.");

            final Address[] senders = message.getFrom();
            if (senders == null || senders.length == 0)
            {
                context.getMonitor().error("Cannot retrieve sender information from the message.");
                return null;
            }
            final InternetAddress internetAddress = (InternetAddress) senders[0];
            final String reporterEmail = internetAddress.getAddress();
            if (!TextUtils.verifyEmail(reporterEmail))
            {
                context.getMonitor().error("The email address [" + reporterEmail + "] received was not valid. Ensure that your mail client specified a valid 'From:' mail header. (see JRA-12203)");
                return null;
            }
            String fullName = internetAddress.getPersonal();
            if ((fullName == null) || (fullName.trim().length() == 0))
            {
                fullName = reporterEmail;
            }

            final String password = SecureRandomStringUtils.getInstance().randomAlphanumericString(6);

            if (context.isRealRun())
            {
                reporter = createUserAddToGroup(reporterEmail,password,reporterEmail,fullName,context);
            }
        } catch (final Exception e)
        {
            context.getMonitor().error("Error occurred while automatically creating a new user from email", e);
        }
        return reporter;
    }

    protected User createUserAddToGroup(final String username, String password, final String emailAddress,
                                        final String displayName, MessageHandlerContext context)
            throws Exception
    {
        context.getMonitor().warning("Creating new user " + username);

        ImmutableUser.Builder builder = ImmutableUser.newUser().directoryId(1l)
                .name(username).displayName(displayName).active(true);

        builder.emailAddress(emailAddress.trim());

        final User user;

        if (StringUtils.isEmpty(password))
        {
            password = userManager.generateRandomPassword();
        }

        user = ComponentAccessor.getCrowdService().addUser(builder.toUser(), password);
        if(!StringUtils.isEmpty(newUserGroupName))
        {
            Group g = ComponentAccessor.getUserUtil().getGroupObject(newUserGroupName);
            if(g==null)
            {
                context.getMonitor().warning("cannnot find group " + newUserGroupName);
            }
            else
            {
                ComponentAccessor.getUserUtil().addUserToGroup(g, user);
                context.getMonitor().warning("added to group "+newUserGroupName);
            }
        }
        return user;
    }

    /**
     * Extract the 'Precedence' header value from the message
     *
     * @param message message
     * @return 'Precedence' header
     * @throws javax.mail.MessagingException in case of extraction of the header fails
     */
    protected String getPrecedenceHeader(final Message message) throws MessagingException
    {
        final String[] precedenceHeaders = message.getHeader("Precedence");
        String precedenceHeader;

        if ((precedenceHeaders != null) && (precedenceHeaders.length > 0))
        {
            precedenceHeader = precedenceHeaders[0];

            if (!StringUtils.isBlank(precedenceHeader))
            {
                return precedenceHeader;
            }
        }
        return null;
    }

    protected boolean isDeliveryStatus(final Message message) throws MessagingException
    {
        final String contentType = message.getContentType();
        if ("multipart/report".equalsIgnoreCase(MailUtils.getContentType(contentType)))
        {
            return contentType.toLowerCase().contains("report-type=delivery-status");
        } else
        {
            return false;
        }
    }

    protected boolean isAutoSubmitted(final Message message) throws MessagingException
    {

        final String[] autoSub = message.getHeader("Auto-Submitted");
        if (autoSub != null)
        {
            for (final String auto : autoSub)
            {
                if (!"no".equalsIgnoreCase(auto))
                {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Records the Message-ID of incoming emails.
     * <p/>
     * As of JIRA v5.2 we no longer record outgoing Message-IDs - instead we use specially crafted ID that
     * can be parsed when it is received as an "In-Reply-To" header.
     * <p/>
     * However, we still record incoming Message-IDs for a special edge-case (see JRA-30293).
     * Namely when a user emails JIRA and CCs another person (creating a new issue) and the second person does a
     * reply-all directly to the original email. We need to remember the incoming Message-ID in order to create a
     * comment instead of a second issue. Note that we also need to remember the Message-ID for the second email (that
     * created the comment) as it too may get replied to directly...
     *
     * @throws MessagingException
     */
    protected void recordMessageId(final String type, final Message message, final Long issueId, MessageHandlerContext context)
            throws MessagingException
    {
        final String[] messageIds = message.getHeader("Message-Id");
        if ((messageIds != null) && (messageIds.length > 0))
        {
            // Record who the e-mail has come from
            final Address[] froms = message.getFrom();
            String fromAddress = null;
            if ((froms != null) && (froms.length > 0))
            {
                fromAddress = ((InternetAddress) froms[0]).getAddress();
            }
            if (context.isRealRun())
            {
                ComponentAccessor.getMailThreadManager().createMailThread(type, issueId, fromAddress, messageIds[0]);
            }
        }
    }

    protected Issue getAssociatedIssue(final Message message)
    {
        // Test if the message has In-Reply-To header to a message that is associated with an issue
        return ComponentAccessor.getMailThreadManager().getAssociatedIssueObject(message);
    }


    protected I18nHelper getI18nBean()
    {
        return ComponentAccessor.getJiraAuthenticationContext().getI18nHelper();
    }

    /**
     * This method just runs through all recipients of the email and builds up a debug string so that we can see who was
     * a recipient of the email.
     *
     * @param message the message that we can't handle.
     * @param monitor
     */
    private void logCantHandleRecipients(final Message message, MessageHandlerExecutionMonitor monitor)
    {
        final Address[] addresses;
        try
        {
            addresses = message.getAllRecipients();
        } catch (final MessagingException e)
        {
            monitor.info("Cannot handle message. Unable to parse recipient addresses.", e);
            return;
        }

        if ((addresses == null) || (addresses.length == 0))
        {
            monitor.info("Cannot handle message.  No recipient addresses found.");
        } else
        {
            final StringBuilder recipients = new StringBuilder();

            for (int i = 0; i < addresses.length; i++)
            {
                final InternetAddress email = (InternetAddress) addresses[i];
                if (email != null)
                {
                    recipients.append(email.getAddress());
                    if ((i + 1) < addresses.length)
                    {
                        recipients.append(", ");
                    }
                }
            }
            monitor.info("Cannot handle message as the recipient(s) (" + recipients.toString() + ") do not match the catch email " + catchEmail);
        }
    }

    void setFingerPrintPolicy(final String fingerPrintPolicy)
    {
        this.fingerPrintPolicy = fingerPrintPolicy;
    }


    private interface AttachmentHandler
    {
        void handlePart(Part part, Message containingMessage) throws IOException, MessagingException;

        void summarize();
    }

    private boolean isPartInline(final Part part) throws MessagingException
    {

        boolean inline = false;

        // an inline part is only considered inline if its also got a filename...
        final String disposition = part.getDisposition();
        if (Part.INLINE.equalsIgnoreCase(disposition))
        {
            final String file = part.getFileName();
            if(!StringUtils.isBlank(file))
            {
                inline = true;
            }
            return inline;
        }
        return false;
    }


    protected CustomField getToCf()
    {
        String toCfId = ConfigUtil.getString(applicationProperties,ConfigUtil.KEY_TO_CF,"");
        if(!toCfId.isEmpty())
        {
            CustomField cf = ComponentAccessor.getCustomFieldManager().getCustomFieldObject(toCfId);
            if(cf == null)
            {
                log.error("invalid to cf id:"+toCfId);
            }
            return cf;
        }
        return null;
    }

    protected CustomField getCcCf()
    {
        String ccCfId = ConfigUtil.getString(applicationProperties,ConfigUtil.KEY_CC_CF,"");
        if(!ccCfId.isEmpty())
        {
            CustomField cf = ComponentAccessor.getCustomFieldManager().getCustomFieldObject(ccCfId);
            if(cf == null)
            {
                log.error("invalid to cf id:"+ccCfId);
            }
            return cf;
        }
        return null;
    }

    protected String getAddressesString(Address[] addresses, String mailBoxEmailToIgnore)
    {
        if(addresses==null)
        {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for(InternetAddress a : (InternetAddress[])addresses)
        {
            if(!a.getAddress().equalsIgnoreCase(mailBoxEmailToIgnore))
            {
                result.append(a.getAddress().toLowerCase()+",");
            }
        }
        if(result.toString().endsWith(","))
        {
            return result.substring(0,result.length()-1);
        }
        return result.toString();
    }

    protected String mergeString(String[] a, String[] b)
    {
        Set<String> set = new HashSet<String>();
        set.addAll(Arrays.asList(a));
        set.addAll(Arrays.asList(b));
        return StringUtils.join(set,",");
    }
}
