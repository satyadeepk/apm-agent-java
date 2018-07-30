/*-
 * #%L
 * Elastic APM Java agent
 * %%
 * Copyright (C) 2018 Elastic and contributors
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package co.elastic.apm.bci;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassInjector;
import net.bytebuddy.utility.JavaModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public class HelperClassInjector implements AgentBuilder.Transformer {
    private static final Logger logger = LoggerFactory.getLogger(HelperClassInjector.class);

    /**
     * Locates the agent helper classes, which are included in the agent jar
     */
    private final ClassFileLocator helperClassLocator;
    private final Set<ClassLoader> alreadyInjected = Collections.newSetFromMap(new WeakHashMap<ClassLoader, Boolean>());
    private final Map<? extends TypeDescription, byte[]> typeDescriptions;
    private final Collection<String> classNamesToInject;

    HelperClassInjector(Collection<String> classNamesToInject) throws IOException {
        this.classNamesToInject = classNamesToInject;
        this.helperClassLocator = ClassFileLocator.ForClassLoader.of(ClassLoader.getSystemClassLoader());
        this.typeDescriptions = getTypeDefinitions(classNamesToInject);
    }

    @Override
    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription typeDescription, ClassLoader classLoader,
                                            JavaModule javaModule) {
        try {
            synchronized (this) {
                if (!alreadyInjected.contains(classLoader)) {
                    injectClasses(classLoader);
                    alreadyInjected.add(classLoader);
                }
            }
        } catch (IOException e) {
            logger.warn("Could not inject helper classes {} to class loader {}", classNamesToInject, classLoader);
            throw new RuntimeException(e);
        }
        return builder;
    }

    private void injectClasses(@Nullable ClassLoader classLoader) throws IOException {
        logger.debug("Injecting helper classes {} to class loader {}", classNamesToInject, classLoader);
        final ClassInjector classInjector;
        if (classLoader == null) {
            classInjector = ClassInjector.UsingInstrumentation.of(Files.createTempDirectory("elastic-apm").toFile(),
                ClassInjector.UsingInstrumentation.Target.BOOTSTRAP, ElasticApmAgent.getInstrumentation());
        } else {
            classInjector = new ClassInjector.UsingReflection(classLoader);
        }
        classInjector.inject(typeDescriptions);
    }

    private Map<? extends TypeDescription, byte[]> getTypeDefinitions(Collection<String> helperClassNames) throws IOException {
        Map<TypeDescription, byte[]> typeDefinitions = new HashMap<>();
        for (final String helperName : helperClassNames) {
            typeDefinitions.put(new TypeDescription.Latent(helperName, 0, null),
                helperClassLocator.locate(helperName).resolve());
        }
        return typeDefinitions;
    }

}
