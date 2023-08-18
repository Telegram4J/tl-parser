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

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class NameDeduplicator implements Consumer<String>, Supplier<String> {
    private final String base;

    private byte counter;

    private NameDeduplicator(String base) {
        this.base = Objects.requireNonNull(base);
    }

    public static NameDeduplicator create(String base) {
        return new NameDeduplicator(base);
    }

    @Override
    public void accept(String s) {
        if (counter == 0 && s.equals(base) ||
                counter > 0 && (base + counter).equals(s)) {
            counter++;
        }
    }

    @Override
    public String get() {
        return counter == 0 ? base : base + counter;
    }
}
