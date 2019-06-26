/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.driver.internal.core.auth;

import edu.umd.cs.findbugs.annotations.NonNull;
import net.jcip.annotations.ThreadSafe;

/**
 * A simple authentication provider that supports SASL authentication using the PLAIN mechanism for
 * version 3 (or above) of the CQL native protocol.
 *
 * <p>To activate this provider, you will need to define it at runtime, and pass it into the
 * SessionBuilder during session creation.
 *
 * <pre>
 *     SessionBuilder builder =
 *         SessionUtils.baseBuilder()
 *             .withAuth(new RuntimePlainTextAuthProvider("logPrefix", "username", "password"));
 * </pre>
 */
@ThreadSafe
public class RuntimePlainTextAuthProvider extends PlainTextAuthProviderBase {
  private final String username;
  private final String password;

  public RuntimePlainTextAuthProvider(String username, String password) {
    this("", username, password);
  }

  public RuntimePlainTextAuthProvider(String logPrefix, String username, String password) {
    super(logPrefix);
    this.username = username;
    this.password = password;
  }

  @NonNull
  @Override
  protected Credentials getCredentials() {
    return new Credentials(username.toCharArray(), password.toCharArray());
  }
}
