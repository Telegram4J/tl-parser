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

abstract class BaseCompletableRenderer<P extends BaseClassRenderer<?>> implements CompletableRenderer<P> {
    protected final P parent;
    protected final CharSink out;

    protected Stage stage;

    protected BaseCompletableRenderer(P parent, Stage stage) {
        this.parent = parent;
        this.stage = stage;

        out = parent.out.createChild();
    }

    @Override
    public Stage stage() {
        return stage;
    }

    @Override
    public P complete() {
        if (stage != Stage.COMPLETE) {
            complete0();
            parent.complete(this);
            stage = Stage.COMPLETE;
        }
        return parent;
    }

    protected abstract void complete0();
}
