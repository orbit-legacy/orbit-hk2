/*
Copyright (C) 2016 Electronic Arts Inc.  All rights reserved.
Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:
1.  Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.
2.  Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in the
    documentation and/or other materials provided with the distribution.
3.  Neither the name of Electronic Arts, Inc. ("EA") nor the names of
    its contributors may be used to endorse or promote products derived
    from this software without specific prior written permission.
THIS SOFTWARE IS PROVIDED BY ELECTRONIC ARTS AND ITS CONTRIBUTORS "AS IS" AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL ELECTRONIC ARTS OR ITS CONTRIBUTORS BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package com.ea.orbit.container;


import com.ea.orbit.actors.extensions.hk2.HK2LifetimeExtension;
import com.ea.orbit.concurrent.Task;
import com.ea.orbit.container.addons.Addon;
import com.ea.orbit.container.config.ContainerConfig;
import com.ea.orbit.container.config.YAMLConfigReader;
import com.ea.orbit.lifecycle.Startable;
import com.google.common.reflect.ClassPath;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.api.ServiceLocatorFactory;
import org.glassfish.hk2.utilities.ServiceLocatorUtilities;
import org.jvnet.hk2.annotations.Service;

import javax.inject.Singleton;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.logging.Level;

@Singleton
public class Container implements Startable
{
    private static final Logger logger = Logger.getLogger(Container.class.getName());
    private ServiceLocator serviceLocator;
    private ContainerConfig config;
    private String containerName = "orbit-container";
    private List<Class<?>> discoveredClasses = new ArrayList<>();
    private List<Object> discoveredServices = new ArrayList<>();
    private List<Addon> discoveredAddons = new ArrayList<>();

    private List<String> packagesToScan = new ArrayList<>();
    private List<String> classesToScan = new ArrayList<>();

    public Container()
    {

    }

    public Container(String containerName)
    {
        this.setContainerName(containerName);
    }

    public Task start()
    {
        logger.info("Starting Orbit Container");

        try
        {
            // Read configuration
            config = YAMLConfigReader.readConfig();

            // Create the DI container
            ServiceLocatorFactory factory = ServiceLocatorFactory.getInstance();
            serviceLocator = factory.create(containerName);
            ServiceLocatorUtilities.addOneConstant(getServiceLocator(), this);

            // Discover addons
            discoverAddons();

            // Crawl the packages and make the container aware of them
            crawlPackages();
        }
        catch(Exception e)
        {
            logger.log(Level.SEVERE, e.toString());
        }


        // Initialize singletons/services
        initServices();

        logger.info("Container successfully started");

        return Task.done();
    }

    public Task stop()
    {
        destroyServices();

        return Task.done();
    }

    private void discoverAddons() throws IOException, InstantiationException, IllegalAccessException
    {
        final ClassPath classPath = ClassPath.from(Container.class.getClassLoader());

        final Set<ClassPath.ClassInfo> classInfos = classPath.getTopLevelClassesRecursive("com.ea.orbit.container.addons");

        for (final ClassPath.ClassInfo classInfo : classInfos)
        {
            Class addonClass = classInfo.load();
            if(!addonClass.isInterface() && Addon.class.isAssignableFrom(addonClass))
            {
                Addon addon = (Addon) addonClass.newInstance();

                packagesToScan.addAll(addon.getPackagesToScan());
                classesToScan.addAll(addon.getClassesToScan());

                discoveredAddons.add(addon);
            }

        }
    }

    private void initServices()
    {
        for(final Addon addon : discoveredAddons)
        {
            addon.configure(this);
        }

        for(final Object service : discoveredServices)
        {
            getServiceLocator().inject(service);
            getServiceLocator().postConstruct(service);

            if(service instanceof Startable)
            {
                ((Startable) service).start().join();
            }
        }
    }

    private void destroyServices()
    {
        for(final Object service : discoveredServices)
        {
            getServiceLocator().preDestroy(service);

            if(service instanceof Startable)
            {
                ((Startable) service).stop().join();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void crawlPackages() throws Exception
    {
        getDiscoveredClasses().clear();
        getDiscoveredServices().clear();

        final ClassPath classPath = ClassPath.from(Container.class.getClassLoader());

        // Scan Packages
        final List<String> packages = new ArrayList<>();
        final List<String> configPackages = config.getAsList("orbit.container.packages", String.class);
        if(configPackages != null) packages.addAll(configPackages);
        if(packagesToScan != null) packages.addAll(packagesToScan);
        for (final String currentPackage : packages)
        {
            final Set<ClassPath.ClassInfo> classInfos = classPath.getTopLevelClassesRecursive(currentPackage);

            for (final ClassPath.ClassInfo classInfo : classInfos)
            {
                final Class<?> loadedClass = classInfo.load();
                processClass(loadedClass);
            }
        }

        // Scan classes
        final List<String> classes = new ArrayList<>();
        final List<String> configClasses = config.getAsList("orbit.container.classes", String.class);
        if(configClasses != null) classes.addAll(configClasses);
        if(classesToScan != null) classes.addAll(classesToScan);
        for (final String currentClass : classes)
        {
            processClass(Class.forName(currentClass));
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T processClass(Class<?> classType) throws InstantiationException, IllegalAccessException
    {
        if(!discoveredClasses.contains(classType))
        {
            discoveredClasses.add(classType);

            if (classType.isAnnotationPresent(Singleton.class) || classType.isAnnotationPresent(Service.class))
            {
                // Singletons are a special case as we allow interception

                // Do we have an intercept?
                Object o = config.getAsInstance(classType.getName(), Object.class);
                if (o == null)
                {
                    o = classType.newInstance();
                }

                discoveredServices.add(o);

                ServiceLocatorUtilities.addOneConstant(getServiceLocator(), o);

                return (T) o;
            }

            final Class<?>[] childClasses = classType.getClasses();
            for(Class childClass : childClasses)
            {
                processClass(childClass);
            }
        }
        return null;
    }

    public Object get(Class<?> classType)
    {
        return serviceLocator.getService(classType);
    }

    public void addClassToScan(Class classType)
    {
        addClassToScan(classType.getName());
    }

    public void addClassToScan(String className)
    {
        classesToScan.add(className);
    }

    public void addPackageToScan(String packageName)
    {
        packagesToScan.add(packageName);
    }

    public List<Class<?>> getDiscoveredClasses() {
        return discoveredClasses;
    }

    public List<Object> getDiscoveredServices() {
        return discoveredServices;
    }

    public String getContainerName()
    {
        return containerName;
    }

    public void setContainerName(String containerName)
    {
        this.containerName = containerName;
    }

    public ServiceLocator getServiceLocator()
    {
        return serviceLocator;
    }
}
