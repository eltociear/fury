/*
 * Copyright 2023 The Fury authors
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fury.serializer;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.fury.Fury;
import io.fury.FuryTestBase;
import io.fury.Language;
import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.testng.annotations.Test;

public class ReplaceResolveSerializerTest extends FuryTestBase {

  @Data
  public static class CustomReplaceClass1 implements Serializable {
    public transient String name;

    public CustomReplaceClass1(String name) {
      this.name = name;
    }

    private Object writeReplace() {
      return new Replaced(name);
    }

    private static final class Replaced implements Serializable {
      public String name;

      public Replaced(String name) {
        this.name = name;
      }

      private Object readResolve() {
        return new CustomReplaceClass1(name);
      }
    }
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testCommonReplace(boolean referenceTracking) {
    Fury fury =
        Fury.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(referenceTracking)
            .build();
    CustomReplaceClass1 o1 = new CustomReplaceClass1("abc");
    fury.registerSerializer(CustomReplaceClass1.class, ReplaceResolveSerializer.class);
    fury.registerSerializer(CustomReplaceClass1.Replaced.class, ReplaceResolveSerializer.class);
    serDeCheck(fury, o1);
    assertTrue(
        fury.getClassResolver().getSerializer(o1.getClass()) instanceof ReplaceResolveSerializer);

    ImmutableList<Integer> list1 = ImmutableList.of(1, 2, 3, 4);
    fury.registerSerializer(list1.getClass(), new ReplaceResolveSerializer(fury, list1.getClass()));
    serDeCheck(fury, list1);

    ImmutableMap<String, Integer> map1 = ImmutableMap.of("k1", 1, "k2", 2);
    fury.registerSerializer(map1.getClass(), new ReplaceResolveSerializer(fury, map1.getClass()));
    serDeCheck(fury, map1);
    assertTrue(
        fury.getClassResolver().getSerializer(list1.getClass())
            instanceof ReplaceResolveSerializer);
    assertTrue(
        fury.getClassResolver().getSerializer(map1.getClass()) instanceof ReplaceResolveSerializer);
  }

  @Data
  public static class CustomReplaceClass2 implements Serializable {
    public boolean copy;
    public transient int age;

    public CustomReplaceClass2(boolean copy, int age) {
      this.copy = copy;
      this.age = age;
    }

    // private `writeReplace` is not available to subclass and will be ignored by
    // `java.io.ObjectStreamClass.getInheritableMethod`
    Object writeReplace() {
      if (age > 5) {
        return new Object[] {copy, age};
      } else {
        if (copy) {
          return new CustomReplaceClass2(copy, age);
        } else {
          return this;
        }
      }
    }

    Object readResolve() {
      if (copy) {
        return new CustomReplaceClass2(copy, age);
      }
      return this;
    }
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testWriteReplaceCircularClass(boolean referenceTracking) {
    Fury fury =
        Fury.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(referenceTracking)
            .build();
    fury.registerSerializer(CustomReplaceClass2.class, ReplaceResolveSerializer.class);
    for (Object o :
        new Object[] {
          new CustomReplaceClass2(false, 2), new CustomReplaceClass2(true, 2),
        }) {
      assertEquals(jdkDeserialize(jdkSerialize(o)), o);
      fury.registerSerializer(o.getClass(), ReplaceResolveSerializer.class);
      serDeCheck(fury, o);
    }
    CustomReplaceClass2 o = new CustomReplaceClass2(false, 6);
    Object[] newObj = (Object[]) serDe(fury, (Object) o);
    assertEquals(newObj, new Object[] {o.copy, o.age});
    assertTrue(
        fury.getClassResolver().getSerializer(CustomReplaceClass2.class)
            instanceof ReplaceResolveSerializer);
  }

  public static class CustomReplaceClass3 implements Serializable {
    public Object ref;

    private Object writeReplace() {
      // JDK serialization will update reference table, which change deserialized object
      //  graph, `ref` and `this` will be same.
      return ref;
    }

    private Object readResolve() {
      return ref;
    }
  }

  @Test
  public void testWriteReplaceSameClassCircularRef() {
    Fury fury =
        Fury.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .build();
    fury.registerSerializer(CustomReplaceClass3.class, ReplaceResolveSerializer.class);
    {
      CustomReplaceClass3 o1 = new CustomReplaceClass3();
      o1.ref = o1;
      CustomReplaceClass3 o2 = (CustomReplaceClass3) jdkDeserialize(jdkSerialize(o1));
      assertSame(o2.ref, o2);
      CustomReplaceClass3 o3 = (CustomReplaceClass3) serDe(fury, o1);
      assertSame(o3.ref, o3);
    }
    {
      CustomReplaceClass3 o1 = new CustomReplaceClass3();
      CustomReplaceClass3 o2 = new CustomReplaceClass3();
      o1.ref = o2;
      o2.ref = o1;
      {
        CustomReplaceClass3 newObj1 = (CustomReplaceClass3) jdkDeserialize(jdkSerialize(o1));
        // reference relationship updated by `CustomReplaceClass4.writeReplace`.
        assertSame(newObj1.ref, newObj1);
        assertSame(((CustomReplaceClass3) newObj1.ref).ref, newObj1);
      }
      {
        CustomReplaceClass3 newObj1 = (CustomReplaceClass3) serDe(fury, o1);
        // reference relationship updated by `CustomReplaceClass4.writeReplace`.
        assertSame(newObj1.ref, newObj1);
        assertSame(((CustomReplaceClass3) newObj1.ref).ref, newObj1);
      }
    }
  }

  public static class CustomReplaceClass4 implements Serializable {
    public Object ref;

    private Object writeReplace() {
      // return ref will incur infinite loop in java.io.ObjectOutputStream.writeObject0
      // for jdk serialization.
      return this;
    }

    private Object readResolve() {
      return ref;
    }
  }

  @Test
  public void testWriteReplaceDifferentClassCircularRef() {
    Fury fury =
        Fury.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .build();
    fury.registerSerializer(CustomReplaceClass3.class, ReplaceResolveSerializer.class);
    fury.registerSerializer(CustomReplaceClass4.class, ReplaceResolveSerializer.class);
    CustomReplaceClass3 o1 = new CustomReplaceClass3();
    CustomReplaceClass4 o2 = new CustomReplaceClass4();
    o1.ref = o2;
    o2.ref = o1;
    {
      CustomReplaceClass4 newObj1 = (CustomReplaceClass4) jdkDeserialize(jdkSerialize(o1));
      assertSame(newObj1.ref, newObj1);
      assertSame(((CustomReplaceClass4) newObj1.ref).ref, newObj1);
    }
    {
      CustomReplaceClass4 newObj1 = (CustomReplaceClass4) serDe(fury, (Object) o1);
      assertSame(newObj1.ref, newObj1);
      assertSame(((CustomReplaceClass4) newObj1.ref).ref, newObj1);
    }
  }

  public static class Subclass1 extends CustomReplaceClass2 {
    int state;

    public Subclass1(boolean copy, int age, int state) {
      super(copy, age);
      this.state = state;
    }

    Object writeReplace() {
      if (age > 5) {
        return new Object[] {copy, age};
      } else {
        if (copy) {
          return new Subclass1(copy, age, state);
        } else {
          return this;
        }
      }
    }

    Object readResolve() {
      if (copy) {
        return new Subclass1(copy, age, state);
      }
      return this;
    }
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testWriteReplaceSubClass(boolean referenceTracking) {
    Fury fury =
        Fury.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(referenceTracking)
            .build();
    fury.registerSerializer(CustomReplaceClass2.class, ReplaceResolveSerializer.class);
    fury.registerSerializer(Subclass1.class, ReplaceResolveSerializer.class);
    for (Object o :
        new Object[] {
          new Subclass1(false, 2, 10), new Subclass1(true, 2, 11),
        }) {
      assertEquals(jdkDeserialize(jdkSerialize(o)), o);
      fury.registerSerializer(o.getClass(), ReplaceResolveSerializer.class);
      serDeCheck(fury, o);
    }
    Subclass1 o = new Subclass1(false, 6, 12);
    Object[] newObj = (Object[]) serDe(fury, (Object) o);
    assertEquals(newObj, new Object[] {o.copy, o.age});
    assertTrue(
        fury.getClassResolver().getSerializer(Subclass1.class) instanceof ReplaceResolveSerializer);
  }

  public static class Subclass2 extends CustomReplaceClass2 {
    int state;

    public Subclass2(boolean copy, int age, int state) {
      super(copy, age);
      this.state = state;
    }

    private void writeObject(java.io.ObjectOutputStream s) throws IOException {
      s.defaultWriteObject();
      s.writeInt(state);
    }

    private void readObject(java.io.ObjectInputStream s) throws Exception {
      s.defaultReadObject();
      this.state = s.readInt();
    }
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testWriteReplaceWithWriteObject(boolean referenceTracking) {
    Fury fury =
        Fury.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(referenceTracking)
            .build();
    fury.registerSerializer(CustomReplaceClass2.class, ReplaceResolveSerializer.class);
    fury.registerSerializer(Subclass2.class, ReplaceResolveSerializer.class);
    for (Object o :
        new Object[] {
          new Subclass2(false, 2, 10), new Subclass2(true, 2, 11),
        }) {
      assertEquals(jdkDeserialize(jdkSerialize(o)), o);
      fury.registerSerializer(o.getClass(), ReplaceResolveSerializer.class);
      serDeCheck(fury, o);
    }
    Subclass2 o = new Subclass2(false, 6, 12);
    assertEquals(jdkDeserialize(jdkSerialize(o)), new Object[] {o.copy, o.age});
    Object[] newObj = (Object[]) serDe(fury, (Object) o);
    assertEquals(newObj, new Object[] {o.copy, o.age});
    assertTrue(
        fury.getClassResolver().getSerializer(Subclass2.class) instanceof ReplaceResolveSerializer);
  }

  public static class CustomReplaceClass5 {
    private Object writeReplace() {
      throw new RuntimeException();
    }

    private Object readResolve() {
      throw new RuntimeException();
    }
  }

  public static class Subclass3 extends CustomReplaceClass5 implements Serializable {}

  @Test
  public void testUnInheritableReplaceMethod() {
    Fury fury =
        Fury.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .build();
    fury.registerSerializer(CustomReplaceClass5.class, ReplaceResolveSerializer.class);
    fury.registerSerializer(Subclass3.class, ReplaceResolveSerializer.class);
    assertTrue(jdkDeserialize(jdkSerialize(new Subclass3())) instanceof Subclass3);
    assertTrue(serDe(fury, new Subclass3()) instanceof Subclass3);
  }

  public static class CustomReplaceClass6 {
    Object writeReplace() {
      return 1;
    }
  }

  @Test
  public void testReplaceNotSerializable() {
    Fury fury =
        Fury.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .build();
    fury.registerSerializer(CustomReplaceClass6.class, ReplaceResolveSerializer.class);
    assertThrows(Exception.class, () -> jdkSerialize(new CustomReplaceClass6()));
    assertEquals(serDe(fury, new CustomReplaceClass6()), 1);
  }

  @Data
  @AllArgsConstructor
  public static class SimpleCollectionTest {
    public List<Integer> integerList;
    public ImmutableList<String> strings;
  }

  @Test
  public void testImmutableListResolve() {
    Fury fury1 =
        Fury.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .build();
    Fury fury2 =
        Fury.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .build();
    roundCheck(fury1, fury2, ImmutableList.of(1, 2));
    roundCheck(fury1, fury2, ImmutableList.of("a", "b"));
    roundCheck(
        fury1, fury2, new SimpleCollectionTest(ImmutableList.of(1, 2), ImmutableList.of("a", "b")));
  }

  @Data
  @AllArgsConstructor
  public static class SimpleMapTest {
    public Map<String, Integer> map1;
    public ImmutableMap<Integer, Integer> map2;
  }

  @Test
  public void testImmutableMapResolve() {
    Fury fury1 =
        Fury.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .build();
    Fury fury2 =
        Fury.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .build();
    roundCheck(fury1, fury2, ImmutableMap.of("k", 2));
    roundCheck(fury1, fury2, ImmutableMap.of(1, 2));
    roundCheck(fury1, fury2, new SimpleMapTest(ImmutableMap.of("k", 2), ImmutableMap.of(1, 2)));
  }
}
