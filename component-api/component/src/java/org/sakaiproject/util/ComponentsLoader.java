/**********************************************************************************
 * $URL$
 * $Id$
 ***********************************************************************************
 *
 * Copyright (c) 2005, 2006 The Sakai Foundation.
 * 
 * Licensed under the Educational Community License, Version 1.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at
 * 
 *      http://www.opensource.org/licenses/ecl1.php
 * 
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License.
 *
 **********************************************************************************/

package org.sakaiproject.util;

import java.io.File;
import java.io.FileFilter;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Vector;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.sakaiproject.component.api.ComponentManager;
import org.sakaiproject.component.impl.SpringCompMgr;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

/**
 * <p>
 * Load the available Sakai components into the shared component manager's Spring ApplicationContext
 * </p>
 */
public class ComponentsLoader implements org.sakaiproject.component.api.ComponentsLoader
{
	/** Our logger */
	private static Log M_log = LogFactory.getLog(ComponentsLoader.class);

	public ComponentsLoader()
	{
	}

	/**
	 * 
	 */
	public void load(ComponentManager mgr, String componentsRoot)
	{
		try
		{
			// get the ComponentManager's AC - assuming this is a SpringCompMgr. If not, this will throw.
			ConfigurableApplicationContext ac = ((SpringCompMgr) mgr).getApplicationContext();

			// get a list of the folders in the root
			File root = new File(componentsRoot);

			// make sure it's a dir.
			if (!root.isDirectory())
			{
				M_log.warn("load: root not directory: " + componentsRoot);
				return;
			}

			// what component packages are there?
			File[] packages = root.listFiles();

			if (packages == null)
			{
				M_log.warn("load: empty directory: " + componentsRoot);
				return;
			}

			// for testing, we might reverse load order
			final int reverse = System.getProperty("sakai.components.reverse.load") != null ? -1 : 1;

			// assure a consistent order - sort these files
			Arrays.sort(packages, new Comparator()
			{
				public int compare(Object o1, Object o2)
				{
					File f1 = (File) o1;
					File f2 = (File) o2;
					int sort = f1.compareTo(f2);
					return sort * reverse;
				}
			});

			M_log.info("load: loading components from: " + componentsRoot);

			// process the packages
			for (int p = 0; p < packages.length; p++)
			{
				// if a valid components directory
				if (validComponentsPackage(packages[p]))
				{
					loadComponentPackage(packages[p], ac);
				}
				else
				{
					M_log.warn("load: skipping non-package entry: " + packages[p]);
				}
			}
		}
		catch (Throwable t)
		{
			M_log.warn("load: exception: " + t);
		}
	}

	/**
	 * Load one component package into the AC
	 * 
	 * @param packageRoot
	 *        The file path to the component package
	 * @param ac
	 *        The ApplicationContext to load into
	 */
	protected void loadComponentPackage(File dir, ConfigurableApplicationContext ac)
	{
		// setup the classloader onto the thread
		ClassLoader current = Thread.currentThread().getContextClassLoader();
		ClassLoader loader = newPackageClassLoader(dir);

		M_log.info("loadComponentPackage: " + dir);

		Thread.currentThread().setContextClassLoader(loader);

		File xml = null;

		try
		{
			// load this xml file
			File webinf = new File(dir, "WEB-INF");
			xml = new File(webinf, "components.xml");

			// make a reader
			XmlBeanDefinitionReader reader = new XmlBeanDefinitionReader((BeanDefinitionRegistry) ac.getBeanFactory());
			
			// In Spring 2, classes aren't loaded during bean parsing unless this
			// classloader property is set.
			reader.setBeanClassLoader(loader);
			
			Resource[] beanDefs = null;
			
			// Load the demo components, if necessary
			File demoXml = new File(webinf, "components-demo.xml");
			if("true".equalsIgnoreCase(System.getProperty("sakai.demo")))
			{
				if(M_log.isDebugEnabled()) M_log.debug("Attempting to load demo components");
				if(demoXml.exists())
				{
					if(M_log.isInfoEnabled()) M_log.info("Loading demo components from " + dir);
					beanDefs = new Resource[]
					{
							new FileSystemResource(xml.getCanonicalPath()),
							new FileSystemResource(demoXml.getCanonicalPath())
					};
				}
			}
			else
			{
				if(demoXml.exists())
				{
					// Only log that we're skipping the demo components if they exist
					if(M_log.isInfoEnabled()) M_log.info("Skipping demo components from " + dir);
				}
			}
			
			if(beanDefs == null)
			{
				beanDefs = new Resource[] {new FileSystemResource(xml.getCanonicalPath())};
			}
			
			reader.loadBeanDefinitions(beanDefs);
		}
		catch (Throwable t)
		{
			M_log.warn("loadComponentPackage: exception loading: " + xml + " : " + t,t);
		}
		finally
		{
			// restore the context loader
			Thread.currentThread().setContextClassLoader(current);
		}
	}

	/**
	 * Test if this File is a valid components package directory.
	 * 
	 * @param dir
	 *        The file to test
	 * @return true if it is a valid components package directory, false if not.
	 */
	protected boolean validComponentsPackage(File dir)
	{
		// valid if this is a directory with a WEB-INF directory below with a components.xml file
		if ((dir != null) && (dir.isDirectory()))
		{
			File webinf = new File(dir, "WEB-INF");
			if ((webinf != null) && (webinf.isDirectory()))
			{
				File xml = new File(webinf, "components.xml");
				if ((xml != null) && (xml.isFile()))
				{
					return true;
				}
			}
		}

		return false;
	}

	/**
	 * Create the class loader for this component package
	 * 
	 * @param dir
	 *        The package's root directory.
	 * @return A class loader, whose parent is this class's loader, which has the classes/ and jars for this component.
	 */
	protected ClassLoader newPackageClassLoader(File dir)
	{
		// collect as a List, turn into an array after
		List urls = new Vector();

		File webinf = new File(dir, "WEB-INF");

		// put classes/ on the classpath
		File classes = new File(webinf, "classes");
		if ((classes != null) && (classes.isDirectory()))
		{
			try
			{
				URL url = new URL("file:" + classes.getCanonicalPath() + "/");
				urls.add(url);
			}
			catch (Throwable t)
			{
			}
		}

		// put each .jar file onto the classpath
		File lib = new File(webinf, "lib");
		if ((lib != null) && (lib.isDirectory()))
		{
			File[] jars = lib.listFiles(new FileFilter()
			{
				public boolean accept(File file)
				{
					return (file.isFile() && file.getName().endsWith(".jar"));
				}
			});

			if (jars != null)
			{
				for (int j = 0; j < jars.length; j++)
				{
					try
					{
						URL url = new URL("file:" + jars[j].getCanonicalPath());
						urls.add(url);
					}
					catch (Throwable t)
					{
					}
				}
			}
		}

		// make the array from the list
		URL[] urlArray = (URL[]) urls.toArray(new URL[urls.size()]);

		// make the classloader - my loader is parent
		URLClassLoader loader = new URLClassLoader(urlArray, getClass().getClassLoader());

		return loader;
	}
}
