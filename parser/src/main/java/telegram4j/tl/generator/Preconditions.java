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

import java.util.function.Supplier;

public class Preconditions {

    public static void requireArgument(boolean state, String text) {
        if (!state) {
            throw new IllegalArgumentException(text);
        }
    }

    public static void requireArgument(boolean state, Supplier<String> text) {
        if (!state) {
            throw new IllegalArgumentException(text.get());
        }
    }

    public static void requireState(boolean state, String text) {
        if (!state) {
            throw new IllegalStateException(text);
        }
    }

    public static void requireState(boolean state, Supplier<String> text) {
        if (!state) {
            throw new IllegalStateException(text.get());
        }
    }
}
