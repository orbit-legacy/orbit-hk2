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


import com.ea.orbit.container.config.ContainerConfig;
import com.ea.orbit.container.config.YAMLConfigReader;
import com.google.common.reflect.ClassPath;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.api.ServiceLocatorFactory;
import org.glassfish.hk2.utilities.ServiceLocatorUtilities;
import org.jvnet.hk2.annotations.Service;

import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.google.common.reflect.ClassPath.*;

@Singleton
public class Container
{
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(Container.class);

    private ContainerConfig config;
    protected ServiceLocator serviceLocator;

    private List<Class<?>> discoveredClasses = new ArrayList<>();
    private List<Object> discoveredServices = new ArrayList<>();

    private List<String> packagesToScan;
    private List<String> classesToScan;

    public Container()
    {

    }

    public void start()
    {
        logger.info("Starting Orbit Container");

        // Read configuration
        config = YAMLConfigReader.readConfig();

        // Create the DI container
        ServiceLocatorFactory factory = ServiceLocatorFactory.getInstance();
        serviceLocator = factory.create(UUID.randomUUID().toString());
        ServiceLocatorUtilities.addOneConstant(serviceLocator, this);

        try
        {
            // Crawl the packages and make the container aware of them
            crawlPackages();
        }
        catch(Exception e)
        {
            logger.error(e.toString());
        }


        // Initialize singletons/services
        initServices();
    }

    public void stop()
    {
        destroyServices();
    }

    private void initServices()
    {
        for(final Object service : getDiscoveredServices())
        {
            serviceLocator.inject(service);
            serviceLocator.postConstruct(service);
        }
    }

    private void destroyServices()
    {
        for(final Object service : getDiscoveredServices())
        {
            serviceLocator.preDestroy(service);
        }
    }

    private void crawlPackages() throws Exception
    {
        getDiscoveredClasses().clear();
        getDiscoveredServices().clear();

        final ClassPath classPath = from(Container.class.getClassLoader());

        // Scan Packages
        final List<String> packages = config.getAsList("orbit.container.packages", String.class);
        if(packagesToScan != null) packages.addAll(packagesToScan);
        for (final String currentPackage : packages)
        {
            final Set<ClassInfo> classInfos = classPath.getTopLevelClassesRecursive(currentPackage);

            for (final ClassInfo classInfo : classInfos)
            {
                final Class<?> loadedClass = classInfo.load();
                processClass(loadedClass);
            }
        }

        // Scan classes
        final List<String> classes = config.getAsList("orbit.container.classes", String.class);
        if(classesToScan != null) classes.addAll(classesToScan);
        for (final String currentClass : classes)
        {
            processClass(Class.forName(currentClass));
        }

        try
        {
            // Stage is special, we want it to start
            processClass(Class.forName("com.ea.orbit.actors.Stage"));
        }
        catch(ClassNotFoundException e)
        {
            // Eat it
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T processClass(Class<?> classType) throws InstantiationException, IllegalAccessException
    {
            getDiscoveredClasses().add(classType);

            if (classType.isAnnotationPresent(Singleton.class) || classType.isAnnotationPresent(Service.class)) {
                // Singletons are a special case as we allow interception

                // Do we have an intercept?
                Object o = config.getAsInstance(classType.getName(), Object.class);
                if (o == null)
                {
                    o = classType.newInstance();
                }

                getDiscoveredServices().add(o);

                ServiceLocatorUtilities.addOneConstant(serviceLocator, o);

                return (T) o;
            }

        return null;
    }

    public List<String> getPackagesToScan() {
        return packagesToScan;
    }

    public void setPackagesToScan(List<String> packagesToScan) {
        this.packagesToScan = packagesToScan;
    }

    public List<String> getClassesToScan() {
        return classesToScan;
    }

    public void setClassesToScan(List<String> classesToScan) {
        this.classesToScan = classesToScan;
    }

    public List<Class<?>> getDiscoveredClasses() {
        return discoveredClasses;
    }

    public List<Object> getDiscoveredServices() {
        return discoveredServices;
    }
}
