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
package telegram4j.tl.generator.renderer;

public interface CompletableRenderer<P> {

    Stage stage();

    P complete();

    class Stage implements Comparable<Stage> {
        public static final Stage ANNOTATIONS = mandatory(-1, "ANNOTATIONS");
        public static final Stage MODIFIERS = mandatory(0, "MODIFIERS");
        public static final Stage TYPE_VARIABLES = mandatory(1, "TYPE_VARIABLES");
        public static final Stage PROCESSING = mandatory(Integer.MAX_VALUE - 1, "PROCESSING");
        public static final Stage COMPLETE = mandatory(Integer.MAX_VALUE, "COMPLETE");

        public final int index;
        public final String name;
        public final boolean optional;

        Stage(int index, String name, boolean optional) {
            this.index = index;
            this.name = name;
            this.optional = optional;
        }

        public static Stage optional(int index, String name) {
            return new Stage(index, name, true);
        }

        public static Stage mandatory(int index, String name) {
            return new Stage(index, name, false);
        }

        @Override
        public int compareTo(Stage o) {
            return Integer.compare(index, o.index);
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
