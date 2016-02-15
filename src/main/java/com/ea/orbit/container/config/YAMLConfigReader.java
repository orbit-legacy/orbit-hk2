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

package com.ea.orbit.container.config;

import com.ea.orbit.actors.core.shaded.org.yaml.snakeyaml.Yaml;
import com.ea.orbit.exception.UncheckedException;
import com.ea.orbit.util.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class YAMLConfigReader
{
    public static ContainerConfig readConfig()
    {
        ContainerConfig newConfig = new ContainerConfigImpl();
        newConfig.putAll(System.getProperties());

        try
        {
            final URL res = ContainerConfig.class.getResource("/conf/orbit.yaml");
            if (res != null)
            {
                final Set<String> activeProfiles = Stream.of(System.getProperty("orbit.profiles", "").split(",")).collect(Collectors.toSet());
                Map<String, Object> props = readProperties(activeProfiles, res.openStream());

                if(props != null)
                {
                    newConfig.putAll(props);
                }
            }
        }
        catch(IOException e)
        {

        }

        return newConfig;
    }

    private static Map<String, Object> readProperties(final Set<String> activeProfiles, final InputStream in) throws IOException
    {
        String inputStreamString = IOUtils.toString(new InputStreamReader(in, "UTF-8"));
        Yaml yaml = new Yaml();
        final Iterable<Object> iter = yaml.loadAll(substituteVariables(inputStreamString));
        final Map<String, Object> newProperties = new LinkedHashMap<>();
        iter.forEach(item -> {
            final Map<String, Object> section = (Map<String, Object>) item;
            final Object sectionProfile = section.get("orbit.profile");
            if (sectionProfile == null || activeProfiles.contains(sectionProfile))
            {
                // if no profile or if the profile is in the current list of profiles.
                newProperties.putAll(section);
            }
        });

        return newProperties;
    }

    private static String substituteVariables(String input)
    {
        StringBuilder sb = new StringBuilder(input);
        int endIndex = -1;
        int startIndex = sb.indexOf("${");
        while (startIndex > -1)
        {
            endIndex = sb.indexOf("}", startIndex);
            if (endIndex == -1)
            {
                throw new UncheckedException("Invalid config file. Could not find a closing curly bracket '}' for variable at line:" + sb.substring(0, startIndex).split("\n").length);
            }

            String propertyString = sb.substring(startIndex + 2, endIndex);
            if (propertyString.indexOf('\n') > -1)
            {
                throw new UncheckedException("Invalid config file. File contains multi-line variable, possibly missing curly bracket '}' at line: " +  sb.substring(0, startIndex).split("\n").length);
            }

            String variableReplacement = getProperty(propertyString);
            if (variableReplacement != null)
            {
                sb.replace(startIndex, endIndex + 1, variableReplacement);
                endIndex = startIndex + variableReplacement.length();
            } else
            {
                throw new UncheckedException("Could not find a value for property '" + propertyString + "'");
            }
            startIndex = sb.indexOf("${", endIndex);
        }

        return sb.toString();
    }

    private static String getProperty(String propertyString)
    {
        int index = propertyString.indexOf(':');
        if (index > -1)
        {
            String propertyName = propertyString.substring(0, index);
            String defaultValue = propertyString.substring(index + 1);
            if (defaultValue != null && !defaultValue.isEmpty())
            {
                defaultValue = defaultValue.trim();
            }

            return getSystemOrEnvironmentVar(propertyName, defaultValue);
        }

        return getSystemOrEnvironmentVar(propertyString, null);
    }

    private static String getSystemOrEnvironmentVar(String propertyName, String defaultValue)
    {
        String returnValue = System.getProperty(propertyName);

        if(returnValue == null)
        {
            returnValue = System.getenv(propertyName);
        }

        if(returnValue == null)
        {
            returnValue = defaultValue;
        }

        return returnValue;
    }
}
