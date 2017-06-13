package com.easesolutions.jira.plugins.easemailhandler.servlet;


import com.atlassian.configurable.ObjectConfigurationException;

import com.atlassian.jira.ComponentManager;
import com.atlassian.jira.component.ComponentAccessor;

import com.atlassian.jira.plugin.ComponentClassManager;

import com.atlassian.jira.security.Permissions;
import com.atlassian.jira.service.services.file.AbstractMessageHandlingService;
import com.atlassian.jira.service.services.file.FileService;
import com.atlassian.jira.service.services.mail.MailFetcherService;
import com.atlassian.jira.web.util.AuthorizationSupport;
import com.atlassian.plugin.PluginAccessor;
import com.atlassian.plugin.util.ContextClassLoaderSwitchingUtil;
import com.opensymphony.module.propertyset.map.MapPropertySet;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

public class RunNowServlet extends HttpServlet {
    private final PluginAccessor pluginAccessor;
    public RunNowServlet(PluginAccessor pluginAccessor)
    {
        this.pluginAccessor = pluginAccessor;
    }

    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        if (!ComponentAccessor.getComponentOfType(AuthorizationSupport.class).hasPermission(Permissions.ADMINISTER))
        {
            resp.setStatus(401);
            return;
        }

        PrintWriter out = resp.getWriter();

        String mailServerId = req.getParameter("mailServerId");
        String foldername = req.getParameter("foldername");
        String forwardEmail = req.getParameter("forwardEmail");
        String handlerParameter = req.getParameter("handlerParameter");
        String handler = req.getParameter("handler");
        String serviceType=req.getParameter("serviceType");
        try
        {
            ComponentClassManager componentClassManager = ComponentManager.getComponentInstanceOfType(ComponentClassManager.class);


            final AbstractMessageHandlingService service = "mail".equals(serviceType)?
                    (AbstractMessageHandlingService)componentClassManager.newInstance(MailFetcherService.class.getName())
                    :(AbstractMessageHandlingService)componentClassManager.newInstance(FileService.class.getName());
            if (service != null)
            {
                final Map<String, String> serviceParams = new HashMap<String, String>();
                serviceParams.put(MailFetcherService.KEY_MAIL_SERVER,mailServerId);
                serviceParams.put(MailFetcherService.FOLDER_NAME_KEY,foldername);
                serviceParams.put(FileService.KEY_SUBDIRECTORY,foldername);
                serviceParams.put(AbstractMessageHandlingService.KEY_HANDLER,handler);
                serviceParams.put(MailFetcherService.FORWARD_EMAIL, forwardEmail);
                serviceParams.put(AbstractMessageHandlingService.KEY_HANDLER_PARAMS,handlerParameter);

                final MapPropertySet set = new MapPropertySet();
                set.setMap(serviceParams);

                ContextClassLoaderSwitchingUtil.runInContext(service.getClass().getClassLoader(), new Runnable() {
                    @Override
                    public void run() {
                        try {
                            service.init(set);
                        } catch (ObjectConfigurationException e) {
                            throw new RuntimeException(e);
                        }
                        service.run();
                    }
                });
            }
        }
        catch (Exception ex)
        {
            out.write(ex.getMessage());
        }
    }
}
