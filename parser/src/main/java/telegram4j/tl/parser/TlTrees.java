/*
 * Copyright 2023 Telegram4J
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package telegram4j.tl.parser;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.immutables.value.Value;
import reactor.util.annotation.Nullable;

import java.util.List;

@Value.Enclosing
public class TlTrees {

    @Value.Immutable(lazyhash = true)
    @JsonSerialize(as = ImmutableTlTrees.Scheme.class)
    @JsonDeserialize(as = ImmutableTlTrees.Scheme.class)
    public static abstract class Scheme {

        @Nullable
        public abstract String version();

        public abstract List<Type> constructors();

        public abstract List<Type> methods();
    }

    @Value.Immutable
    @JsonDeserialize(using = TypeDeserializer.class)
    public static abstract class Type {

        public abstract Kind kind();

        public abstract String id();

        public abstract String name();

        public abstract List<Parameter> parameters();

        public abstract String type();

        public enum Kind {
            CONSTRUCTOR,
            METHOD
        }
    }

    @Value.Immutable
    @JsonSerialize(as = ImmutableTlTrees.Parameter.class)
    @JsonDeserialize(as = ImmutableTlTrees.Parameter.class)
    public static abstract class Parameter {

        public abstract String name();

        public abstract String type();
    }
}
