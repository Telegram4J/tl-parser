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
package telegram4j.tl.generator;

import reactor.util.annotation.Nullable;
import telegram4j.tl.generator.renderer.TypeRef;

import java.util.EnumSet;
import java.util.Objects;

import static telegram4j.tl.generator.SchemaGeneratorConsts.*;

class ValueAttribute {
    public final String name;

    public TypeRef type;
    public EnumSet<Flag> flags = EnumSet.noneOf(ValueAttribute.Flag.class);
    @Nullable
    public String flagsName;
    @Nullable
    public String flagMask;
    public int flagPos = -1;
    @Nullable
    public String jsonName;
    public short maxSize = -1; // size restriction in bytes; used only for int256 and int128

    @Nullable
    private Names names;

    ValueAttribute(String name) {
        this.name = name;
    }

    public Names names() {
        if (names == null) {
            names = new Names();
        }
        return names;
    }

    public String flagsName() {
        Objects.requireNonNull(flagsName);
        return flagsName;
    }

    public String flagMask() {
        Objects.requireNonNull(flagMask);
        return flagMask;
    }

    class Names {
        public final String singular = Depluralizer.instance().apply(name);
        public final String initBit = Style.initBit.apply(name, Naming.As.SCREMALIZED);
        public final String add = Style.add.apply(singular);
        public final String addv = Style.add.apply(name);
        public final String set = Style.set.apply(name);
        public final String addAll = Style.addAll.apply(name);
    }

    enum Flag {
        BIT_FLAG,
        BIT_SET, // implicitly optional attribute
        OPTIONAL
    }
}
