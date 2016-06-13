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

package cloud.orbit.container;


import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.api.ServiceLocatorFactory;
import org.glassfish.hk2.utilities.ServiceLocatorUtilities;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.reflect.ClassPath;

import cloud.orbit.annotation.Config;
import cloud.orbit.concurrent.Task;
import cloud.orbit.container.addons.Addon;
import cloud.orbit.container.config.ContainerConfig;
import cloud.orbit.container.config.YAMLConfigReader;
import cloud.orbit.exception.UncheckedException;
import cloud.orbit.lifecycle.Startable;
import cloud.orbit.reflect.ClassCache;
import cloud.orbit.reflect.FieldDescriptor;

import javax.inject.Singleton;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Singleton
public class Container implements Startable
{
    private static final Logger logger = LoggerFactory.getLogger(Container.class);
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
        logger.info("Starting orbit container...");

        try
        {
            // Read configuration
            if(config == null)
            {
                config = YAMLConfigReader.readConfig();
            }

            // Override the name if needed
            containerName = config.getAsString("orbit.container.name", containerName);

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
            logger.error(e.toString());
        }


        // Initialize singletons/services
        initServices();

        logger.info("Container successfully started.");

        return Task.done();
    }

    public Task stop()
    {
        logger.info("Stopping orbit container...");

        destroyServices();

        logger.info("Container successfully stopped.");

        return Task.done();
    }

    private void discoverAddons() throws IOException, InstantiationException, IllegalAccessException
    {
        final ClassPath classPath = ClassPath.from(Container.class.getClassLoader());

        final Set<ClassPath.ClassInfo> classInfos = classPath.getTopLevelClassesRecursive("cloud.orbit.container.addons");

        classInfos.stream()
                .map(ClassPath.ClassInfo::load)
                .filter(c -> !c.isInterface() &&  Addon.class.isAssignableFrom(c))
                .forEach(addonClass ->
                {
                    Addon addon = null;
                    try
                    {
                        addon = (Addon) addonClass.newInstance();
                    }
                    catch(Exception e)
                    {
                        throw new UncheckedException(e);
                    }


                    packagesToScan.addAll(addon.getPackagesToScan());
                    classesToScan.addAll(addon.getClassesToScan());

                    discoveredAddons.add(addon);
                });

        logger.info("Container discovered {} addons.", discoveredAddons.size());
    }

    private void initServices()
    {
        // Configure addons
        discoveredAddons.stream().forEach(a -> a.configure(this));

        // Configure and start services
        discoveredServices.stream()
                .forEach(service ->
                {
                    this.inject(service);
                    getServiceLocator().postConstruct(service);

                    if(service instanceof Startable)
                    {
                        ((Startable) service).start().join();
                    }
                });
    }

    private void destroyServices()
    {
        discoveredServices.stream()
                .forEach(service ->
                {
                    getServiceLocator().preDestroy(service);

                    if(service instanceof Startable)
                    {
                        ((Startable) service).stop().join();
                    }
                });
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

        logger.info("Container considered {} classes and discovered {} services.", discoveredClasses.size(), discoveredServices.size());
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

    public void inject(Object o)
    {
        this.inject(o, true);
    }

    public void inject(Object o, boolean injectConfig)
    {
        if(serviceLocator != null)
        {
            serviceLocator.inject(o);
        }

        if(injectConfig)
        {
            try
            {
                this.injectConfig(o);
            }
            catch(IllegalAccessException e)
            {
                throw new UncheckedException(e);
            }
        }
    }



    protected void injectConfig(Object o) throws IllegalAccessException
    {
        for (FieldDescriptor fd : ClassCache.shared.getClass(o.getClass()).getAllInstanceFields())
        {
            injectConfig(o, fd.getField());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    protected void injectConfig(Object o, java.lang.reflect.Field f) throws IllegalAccessException
    {
        final Config configAnnotation = f.getAnnotation(Config.class);
        if (configAnnotation != null)
        {
            if (Modifier.isFinal(f.getModifiers()))
            {
                throw new RuntimeException("Configurable fields should never be final: " + f);
            }

            f.setAccessible(true);

            if (f.getType() == Integer.TYPE || f.getType() == Integer.class)
            {
                f.set(o, config.getAsInt(configAnnotation.value(), (Integer) f.get(o)));
            }
            else if (f.getType() == Boolean.TYPE || f.getType() == Boolean.class)
            {
                f.set(o, config.getAsBoolean(configAnnotation.value(), (Boolean) f.get(o)));
            }
            else if (f.getType() == Long.TYPE || f.getType() == Long.class)
            {
                f.set(o, config.getAsLong(configAnnotation.value(), (Long) f.get(o)));
            }
            else if (f.getType() == String.class)
            {
                f.set(o, config.getAsString(configAnnotation.value(), (String) f.get(o)));
            }
            else if (f.getType().isEnum())
            {
                final String enumValue = config.getAsString(configAnnotation.value(), null);
                if (enumValue != null)
                {
                    f.set(o, Enum.valueOf((Class<Enum>) f.getType(), enumValue));
                }
            }
            else if (List.class.isAssignableFrom(f.getType()))
            {
                if ((config.getAll().get(configAnnotation.value()) != null))
                {
                    final Object val = config.getAll().get(configAnnotation.value());
                    f.set(o, val);
                }
            }
            else if (Set.class.isAssignableFrom(f.getType()))
            {
                if ((config.getAll().get(configAnnotation.value()) != null))
                {
                    final Object val = config.getAll().get(configAnnotation.value());
                    if (val instanceof List)
                    {
                        f.set(o, new LinkedHashSet((List) val));
                    }
                    else
                    {
                        f.set(o, val);
                    }
                }
            }
            else if (config.getAll().get(configAnnotation.value()) != null)
            {
                final Object val = config.getAll().get(configAnnotation.value());
                f.set(o, val);
            }
            else
            {
                throw new UncheckedException("Field type not supported for configuration injection: " + f);
            }
        }
    }

    public <T> T get(Class<T> clazz)
    {
        return this.get(clazz, false);
    }

    public <T> T get(Class<T> clazz, boolean shouldCreateInstance)
    {
        T o = serviceLocator.getService(clazz);
        if(o == null && shouldCreateInstance)
        {
            try
            {
                o = clazz.newInstance();
                this.inject(o);
            }
            catch(Exception e)
            {
                // Eat it
            }
        }
        return o;
    }

    public ContainerConfig getConfiguration()
    {
        return config;
    }

    public void setConfiguration(final ContainerConfig config)
    {
        this.config = config;
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
