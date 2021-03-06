/* Copyright 2016 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.api.codegen.config;

import com.google.api.ResourceDescriptor;
import com.google.api.ResourceReference;
import com.google.api.codegen.ConfigProto;
import com.google.api.codegen.ResourceNameMessageConfigProto;
import com.google.api.codegen.discogapic.transformer.DiscoGapicNamer;
import com.google.api.codegen.discovery.Method;
import com.google.api.codegen.discovery.Schema;
import com.google.api.codegen.util.ProtoParser;
import com.google.api.tools.framework.model.Diag;
import com.google.api.tools.framework.model.DiagCollector;
import com.google.api.tools.framework.model.Field;
import com.google.api.tools.framework.model.MessageType;
import com.google.api.tools.framework.model.ProtoFile;
import com.google.api.tools.framework.model.SimpleLocation;
import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;
import java.util.*;

/** Configuration of the resource name types for all message field. */
@AutoValue
public abstract class ResourceNameMessageConfigs {

  abstract ImmutableMap<String, ResourceNameMessageConfig> getResourceTypeConfigMap();

  /**
   * Get a map from fully qualified message names to Fields, where each field has a resource name
   * defined.
   */
  public abstract ListMultimap<String, FieldModel> getFieldsWithResourceNamesByMessage();

  @VisibleForTesting
  static ResourceNameMessageConfigs createFromAnnotations(
      DiagCollector diagCollector,
      List<ProtoFile> protoFiles,
      ProtoParser parser,
      Map<String, ResourceDescriptorConfig> descriptorConfigMap) {
    ImmutableMap.Builder<String, ResourceNameMessageConfig> builder = ImmutableMap.builder();

    for (ProtoFile protoFile : protoFiles) {
      for (MessageType message : protoFile.getMessages()) {
        ImmutableMap.Builder<String, String> fieldEntityMapBuilder = ImmutableMap.builder();

        String resourceFieldName = null;
        ResourceDescriptor resourceDescriptor = parser.getResourceDescriptor(message);
        if (resourceDescriptor != null) {
          resourceFieldName = resourceDescriptor.getNameField();
          if (Strings.isNullOrEmpty(resourceFieldName)) {
            resourceFieldName = "name"; // Default field containing the resource path.
          }
          Field resourceField = message.lookupField(resourceFieldName);
          String entityName =
              getResourceDescriptorTypeForField(
                  false,
                  diagCollector,
                  descriptorConfigMap,
                  resourceDescriptor.getType(),
                  message,
                  resourceField);
          if (Strings.isNullOrEmpty(entityName)) continue;
          fieldEntityMapBuilder.put(resourceField.getSimpleName(), entityName);
        }

        for (Field field : message.getFields()) {
          if (!parser.hasResourceReference(field)) {
            continue;
          }
          if (field.getSimpleName().equals(resourceFieldName)) {
            // We've already processed the Resource message's "name" field above.
            continue;
          }

          ResourceReference reference = parser.getResourceReference(field);
          boolean isChildReference = !Strings.isNullOrEmpty(reference.getChildType());
          String type = isChildReference ? reference.getChildType() : reference.getType();
          if (type.equals("*")) {
            // This is an AnyResourceNameConfig.
            fieldEntityMapBuilder.put(field.getSimpleName(), "*");
            continue;
          }
          String entityName =
              getResourceDescriptorTypeForField(
                  isChildReference, diagCollector, descriptorConfigMap, type, message, field);
          if (Strings.isNullOrEmpty(entityName)) continue;
          fieldEntityMapBuilder.put(field.getSimpleName(), entityName);
        }
        ImmutableMap<String, String> fieldEntityMap = fieldEntityMapBuilder.build();
        if (fieldEntityMap.size() > 0) {
          ResourceNameMessageConfig messageConfig =
              new AutoValue_ResourceNameMessageConfig(message.getFullName(), fieldEntityMap);
          builder.put(messageConfig.messageName(), messageConfig);
        }
      }
    }
    ImmutableMap<String, ResourceNameMessageConfig> map = builder.build();
    return new AutoValue_ResourceNameMessageConfigs(map, createFieldsByMessage(protoFiles, map));
  }

  private static String getResourceDescriptorTypeForField(
      boolean isChildReference,
      DiagCollector diagCollector,
      Map<String, ResourceDescriptorConfig> descriptorConfigMap,
      String type,
      MessageType message,
      Field field) {
    ResourceDescriptorConfig config = descriptorConfigMap.get(type);
    if (config == null) {
      diagCollector.addDiag(
          Diag.error(
              SimpleLocation.TOPLEVEL,
              "Reference to unknown type \"%s\" on field %s.%s",
              type,
              message.getFullName(),
              field.getFullName()));
      return null;
    }

    String entityName;
    if (isChildReference) {
      // Attempt to resolve the reference to an existing type. If we can't, mark this
      // type as having a child reference, and resolve the reference to the derived
      // parent type.
      List<String> parentPatterns = config.getParentPatterns();
      Optional<ResourceDescriptorConfig> parentConfig =
          descriptorConfigMap
              .values()
              .stream()
              .filter(
                  c ->
                      parentPatterns.size() == c.getPatterns().size()
                          && parentPatterns.containsAll(c.getPatterns()))
              .findFirst();
      if (parentConfig.isPresent()) {
        entityName = parentConfig.get().getDerivedEntityName();
      } else {
        entityName = config.getDerivedParentEntityName();
      }
    } else {
      entityName = config.getDerivedEntityName();
    }
    return entityName;
  }

  @VisibleForTesting
  static ResourceNameMessageConfigs createFromGapicConfigOnly(
      List<ProtoFile> protoFiles, ConfigProto configProto, String defaultPackage) {
    ImmutableMap<String, ResourceNameMessageConfig> map =
        createMapFromGapicConfig(configProto, defaultPackage);
    return new AutoValue_ResourceNameMessageConfigs(map, createFieldsByMessage(protoFiles, map));
  }

  private static ListMultimap<String, FieldModel> createFieldsByMessage(
      List<ProtoFile> protoFiles,
      Map<String, ResourceNameMessageConfig> messageResourceTypeConfigMap) {
    ListMultimap<String, FieldModel> fieldsByMessage = ArrayListMultimap.create();
    Set<String> seenProtoFiles = new HashSet<>();
    for (ProtoFile protoFile : protoFiles) {
      if (!seenProtoFiles.contains(protoFile.getSimpleName())) {
        seenProtoFiles.add(protoFile.getSimpleName());
        for (MessageType msg : protoFile.getMessages()) {
          ResourceNameMessageConfig messageConfig =
              messageResourceTypeConfigMap.get(msg.getFullName());
          if (messageConfig == null) {
            continue;
          }
          for (Field field : msg.getFields()) {
            if (messageConfig.getEntityNameForField(field.getSimpleName()) != null) {
              fieldsByMessage.put(msg.getFullName(), new ProtoField(field));
            }
          }
        }
      }
    }
    return fieldsByMessage;
  }

  private static ImmutableMap<String, ResourceNameMessageConfig> createMapFromGapicConfig(
      ConfigProto configProto, String defaultPackage) {
    ImmutableMap.Builder<String, ResourceNameMessageConfig> builder = ImmutableMap.builder();
    for (ResourceNameMessageConfigProto messageResourceTypesProto :
        configProto.getResourceNameGenerationList()) {
      ResourceNameMessageConfig messageResourceTypeConfig =
          ResourceNameMessageConfig.createResourceNameMessageConfig(
              messageResourceTypesProto, defaultPackage);
      builder.put(messageResourceTypeConfig.messageName(), messageResourceTypeConfig);
    }
    return builder.build();
  }

  static ResourceNameMessageConfigs createMessageResourceTypesConfig(
      DiscoApiModel model, ConfigProto configProto, String defaultPackage) {
    ImmutableMap<String, ResourceNameMessageConfig> messageResourceTypeConfigMap =
        createMapFromGapicConfig(configProto, defaultPackage);

    ListMultimap<String, FieldModel> fieldsByMessage = ArrayListMultimap.create();
    DiscoGapicNamer discoGapicNamer = new DiscoGapicNamer();

    for (Method method : model.getDocument().methods()) {
      String fullName = discoGapicNamer.getRequestMessageFullName(method, defaultPackage);
      ResourceNameMessageConfig messageConfig = messageResourceTypeConfigMap.get(fullName);
      if (messageConfig == null) {
        continue;
      }
      for (Schema property : method.parameters().values()) {
        if (messageConfig.getEntityNameForField(property.getIdentifier()) != null) {
          fieldsByMessage.put(fullName, DiscoveryField.create(property, model));
        }
      }
    }
    return new AutoValue_ResourceNameMessageConfigs(messageResourceTypeConfigMap, fieldsByMessage);
  }

  public boolean isEmpty() {
    return getResourceTypeConfigMap().isEmpty();
  }

  boolean fieldHasResourceName(FieldModel field) {
    return fieldHasResourceName(field.getParentFullName(), field.getSimpleName());
  }

  public boolean fieldHasResourceName(String messageFullName, String fieldSimpleName) {
    return getResourceNameOrNullForField(messageFullName, fieldSimpleName) != null;
  }

  String getFieldResourceName(FieldModel field) {
    return getFieldResourceName(field.getParentFullName(), field.getSimpleName());
  }

  public String getFieldResourceName(String messageSimpleName, String fieldSimpleName) {
    if (!fieldHasResourceName(messageSimpleName, fieldSimpleName)) {
      throw new IllegalArgumentException(
          "Field "
              + fieldSimpleName
              + " of message "
              + messageSimpleName
              + " does not have a resource name.");
    }
    return getResourceNameOrNullForField(messageSimpleName, fieldSimpleName);
  }

  private String getResourceNameOrNullForField(String messageSimpleName, String fieldSimpleName) {
    ResourceNameMessageConfig messageResourceTypeConfig =
        getResourceTypeConfigMap().get(messageSimpleName);
    if (messageResourceTypeConfig == null) {
      return null;
    }
    return messageResourceTypeConfig.getEntityNameForField(fieldSimpleName);
  }
}
