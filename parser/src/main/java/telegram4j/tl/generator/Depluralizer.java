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

import java.util.function.UnaryOperator;

public class Depluralizer implements UnaryOperator<String> {
    private static final Naming NAMING_S_PLURAL = Naming.from("*s");
    private static final Naming NAMING_IES_PLURAL = Naming.from("*ies");

    private static final Depluralizer instance = new Depluralizer();

    private Depluralizer() {}

    public static Depluralizer instance() {
        return instance;
    }

    @Override
    public String apply(String s) {
        String detected = NAMING_IES_PLURAL.detect(s);
        if (detected != null) {
            return detected + 'y';
        }
        detected = NAMING_S_PLURAL.detect(s);
        if (detected != null) {
            return detected;
        }
        return s;
    }
}
