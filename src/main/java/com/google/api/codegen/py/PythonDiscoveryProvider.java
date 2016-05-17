/* Copyright 2016 Google Inc
 *
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
 */
package com.google.api.codegen.py;

import com.google.api.Service;
import com.google.api.tools.framework.snippet.Doc;
import com.google.api.tools.framework.tools.ToolUtil;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimap;
import com.google.protobuf.Method;

import com.google.api.codegen.ApiaryConfig;
import com.google.api.codegen.DiscoveryProvider;
import com.google.api.codegen.GeneratedResult;
import com.google.api.codegen.SnippetDescriptor;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A DiscoveryProvider for generating Python fragments.
 */
public class PythonDiscoveryProvider implements DiscoveryProvider {
  private final PythonDiscoveryContext context;
  private final PythonProvider provider;

  public PythonDiscoveryProvider(Service service, ApiaryConfig apiaryConfig) {
    this.context = new PythonDiscoveryContext(service, apiaryConfig);
    this.provider = new PythonProvider();
  }

  @Override
  public GeneratedResult generateFragments(Method method, SnippetDescriptor snippetDescriptor) {
    return provider.generate(
        method,
        snippetDescriptor,
        context,
        new PythonImportHandler(),
        ImmutableMap.<String, Object>of(),
        "");
  }

  @Override
  public void output(String outputPath,
      Multimap<Method, GeneratedResult> methods)
          throws IOException {
    String root = context.outputRoot();
    Map<String, Doc> files = new LinkedHashMap<>();
    for (GeneratedResult generatedResult : methods.values()) {
      files.put(root + "/" + generatedResult.getFilename(), generatedResult.getDoc());
    }
    ToolUtil.writeFiles(files, outputPath);
  }

  @Override
  public Service getService() {
    return context.getService();
  }
}