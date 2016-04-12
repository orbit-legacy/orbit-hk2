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

package cloud.orbit.actors.extensions.hk2.test;

import cloud.orbit.container.Container;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Created by joe on 3/3/2016.
 */
public class HK2Test
{
    private Container container = null;

    @Before
    public void setupContainer()
    {
        container = new Container();
        container.addPackageToScan("cloud.orbit.actors.extensions.hk2.test");
        container.start().join();
    }

    @After
    public void killContainer()
    {
        container.stop().join();
    }

    @Test
    public void verifySingleton()
    {
        // Check it isn't null
        SingletonClass o = container.get(SingletonClass.class);
        assertNotNull(o);

        // Check we always get the same one
        Object o2 = container.get(SingletonClass.class);
        assertSame(o, o2);

        // Check post construct runs
        assertTrue(o.didPostConstructRun());

        // Check default values are left
        assertEquals(o.getOriginalVar(), "Hello");

        // Check singleton creation interception works
        assertEquals(o.getInterceptVar(), "intercepted");

        // Make sure @Config annotation works
        assertEquals(o.getConfigVar(), "overridden");

        // Make sure @Inject annotation works
        assertNotNull(o.getInjectTest());

    }

    @Test
    public void verifyNonSingletonNull()
    {
        assertNull(container.get(NonSingletonClass.class, false));
    }

    @Test
    public void verifyNonSingletonCreation()
    {
        // Make sure we create one
        NonSingletonClass o = container.get(NonSingletonClass.class, true);
        assertNotNull(o);

        // Make sure each one is unique
        NonSingletonClass o2 = container.get(NonSingletonClass.class, true);
        assertNotSame(o, o2);

        // Make sure @Config annotation works
        assertEquals(o.getConfigTest(), "overridden");

        // Make sure the @Inject annotation worked
        assertNotNull(o.getInjectTest());
    }

}
