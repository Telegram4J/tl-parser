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
package telegram4j.tl.api;

/**
 * Derived interface of the objects from the end-to-end encryption scheme.
 *
 * <p> These objects are never sent to the server as-is; they are serialized into
 * the payload of {@code messages.sendEncrypted*} methods by the client itself.
 * Their identifiers live in a separate id space and may collide with the ones
 * of the main scheme, therefore they are serialized by a dedicated
 * {@code SecretTlSerializer}/{@code SecretTlDeserializer} pair.
 */
public interface SecretObject extends TlObject {

}
