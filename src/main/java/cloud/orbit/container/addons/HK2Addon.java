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

package cloud.orbit.container.addons;

import cloud.orbit.actors.extensions.hk2.HK2LifetimeExtension;
import cloud.orbit.concurrent.Task;
import cloud.orbit.container.Container;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by joe@bioware.com on 2016-02-16.
 */
public class HK2Addon implements Addon
{
    private static final String STAGE_CLASS = "cloud.orbit.actors.Stage";
    private static final String EXTENSION_CLASS = "cloud.orbit.actors.extensions.ActorExtension";
    private static final String ADD_EXTENSION_METHOD = "addExtension";

    @Override
    public List<String> getClassesToScan()
    {
        final List<String> classes = new ArrayList<>();

        try
        {
            final Class stageClass = Class.forName(STAGE_CLASS);
            classes.add(stageClass.getName());
        }
        catch(ClassNotFoundException e)
        {

        }

        return classes;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void postInject(final Container container)
    {
        try
        {
            // Stage is special, we want it to start and to register ourselves if it exists
            final Class stageClass = Class.forName(STAGE_CLASS);
            final Class extensionClass = Class.forName(EXTENSION_CLASS);
            final Object stage = container.get(stageClass);
            if(stage != null)
            {
                final Method addExtensionMethod = stageClass.getMethod(ADD_EXTENSION_METHOD, extensionClass);
                addExtensionMethod.invoke(stage, new HK2LifetimeExtension(container));
            }

        }
        catch(ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e)
        {
            // Eat it
        }
    }
}
