/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.queryengine.plan.relational.function;

import org.apache.iotdb.db.queryengine.plan.relational.utils.TypeUtil;

import org.apache.tsfile.read.common.type.Type;
import org.apache.tsfile.utils.ReadWriteIOUtils;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

public class BoundSignature {

  private final String functionName;
  private final Type returnType;
  private final List<Type> argumentTypes;

  public BoundSignature(String functionName, Type returnType, List<Type> argumentTypes) {
    this.functionName = requireNonNull(functionName, "functionName is null");
    this.returnType = requireNonNull(returnType, "returnType is null");
    this.argumentTypes = argumentTypes;
  }

  /** The absolute canonical name of the function. */
  public String getName() {
    return functionName;
  }

  public Type getReturnType() {
    return returnType;
  }

  public Type getArgumentType(int index) {
    return argumentTypes.get(index);
  }

  public List<Type> getArgumentTypes() {
    return argumentTypes;
  }

  //  public Signature toSignature() {
  //    return Signature.builder()
  //        .returnType(returnType)
  //        .argumentTypes(argumentTypes.stream()
  //            .map(Type::getTypeSignature)
  //            .collect(Collectors.toList()))
  //        .build();
  //  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    BoundSignature that = (BoundSignature) o;
    return Objects.equals(functionName, that.functionName)
        && Objects.equals(returnType, that.returnType)
        && Objects.equals(argumentTypes, that.argumentTypes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(functionName, returnType, argumentTypes);
  }

  @Override
  public String toString() {
    return functionName
        + argumentTypes.stream().map(Type::toString).collect(joining(", ", "(", "):"))
        + returnType;
  }

  public void serialize(ByteBuffer byteBuffer) {
    ReadWriteIOUtils.write(functionName, byteBuffer);
    TypeUtil.serialize(returnType, byteBuffer);
    ReadWriteIOUtils.write(argumentTypes.size(), byteBuffer);
    for (Type type : argumentTypes) {
      TypeUtil.serialize(type, byteBuffer);
    }
  }

  public void serialize(DataOutputStream stream) throws IOException {
    ReadWriteIOUtils.write(functionName, stream);
    TypeUtil.serialize(returnType, stream);
    ReadWriteIOUtils.write(argumentTypes.size(), stream);
    for (Type type : argumentTypes) {
      TypeUtil.serialize(type, stream);
    }
  }

  public static BoundSignature deserialize(ByteBuffer byteBuffer) {
    String functionName = ReadWriteIOUtils.readString(byteBuffer);
    Type returnType = TypeUtil.deserialize(byteBuffer);
    int size = ReadWriteIOUtils.readInt(byteBuffer);
    List<Type> argumentTypes = new ArrayList<>(size);
    while (size-- > 0) {
      argumentTypes.add(TypeUtil.deserialize(byteBuffer));
    }
    return new BoundSignature(functionName, returnType, argumentTypes);
  }
}
