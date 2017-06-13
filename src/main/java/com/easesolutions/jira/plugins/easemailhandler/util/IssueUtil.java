package com.easesolutions.jira.plugins.easemailhandler.util;


import com.atlassian.crowd.embedded.api.User;
import com.atlassian.jira.bc.project.component.ProjectComponent;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.customfields.option.Option;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.label.Label;
import org.apache.commons.lang.builder.ReflectionToStringBuilder;

import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class IssueUtil {


    /**
     * THis function is used to ignore the case, e.g.  User input "product selection" as cf name, we should correct it
     * to "Product Selection" otherwise cannot find such Custom Field
     * @param name
     * @return
     */
    public static CustomField getCorrectCustomField(CustomFieldManager cfm, Issue issue, String name)
    {
        List<CustomField> allCF = cfm.getCustomFieldObjects(issue);
        for(CustomField cf : allCF)
        {
            if(cf.getName().equalsIgnoreCase(name))
            {
                return cf;
            }
        }

        return null;
    }

  public static String getStringifiedVal(Object aVal)
  {
    String ret = null;
    if (aVal != null)
    {
      if ((aVal instanceof List))
      {
        List l = (List)aVal;
        StringBuilder sb = new StringBuilder();
        for (Iterator iterator = l.iterator(); iterator.hasNext(); )
        {
          Object object = iterator.next();
          sb.append(getStringFor(object));
          if (!iterator.hasNext())
            continue;
          sb.append(",");
        }

        ret = sb.toString();
      }
      else if ((aVal instanceof Set))
      {
        Set l = (Set)aVal;
        StringBuilder sb = new StringBuilder();
        for (Iterator iterator = l.iterator(); iterator.hasNext(); )
        {
          Object object = iterator.next();
          sb.append(getStringFor(object));
          if (!iterator.hasNext())
            continue;
          sb.append(",");
        }

        ret = sb.toString();
      }
      else if ((aVal instanceof Object[]))
      {
        Object[] array = (Object[])aVal;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < array.length; i++)
        {
          sb.append(getStringFor(array[i]));
          if (i + 1 >= array.length)
            continue;
          sb.append(",");
        }

      }
      else
      {
        ret = aVal.toString();
      }
    }
    return ret;
  }

  public static String getStringFor(Object object)
  {
    String s = null;
    if ((object instanceof String))
    {
      s = (String)object;
    }
    else if ((object instanceof User))
    {
      User u = (User)object;
      s = u.getName();
    }
    else if ((object instanceof Option))
    {
      s = ((Option)object).getValue();
    }
    else if(object instanceof Label)
    {
        s = ((Label)object).getLabel();
    }
    else if(object instanceof ProjectComponent)
    {
        s = ((ProjectComponent)object).getName();
    }
    else
    {
      s = ReflectionToStringBuilder.toString(object);
    }
    return s;
  }

    

}
