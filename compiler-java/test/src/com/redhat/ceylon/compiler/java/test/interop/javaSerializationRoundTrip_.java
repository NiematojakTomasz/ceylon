package com.redhat.ceylon.compiler.java.test.interop;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Method;
import java.lang.Class;

import com.redhat.ceylon.compiler.java.metadata.Ignore;

@com.redhat.ceylon.compiler.java.metadata.Method
public class javaSerializationRoundTrip_ {
    
    private javaSerializationRoundTrip_() {}
    
    @Ignore
    public static void main(String[] a) throws Exception {
        javaSerializationRoundTrip();
    }
    
    public static void javaSerializationRoundTrip() throws Exception {
        Class cls = Class.forName("com.redhat.ceylon.compiler.java.test.interop.javaSerialization_");
        Method meth = cls.getMethod("javaSerialization");
        final Object o = meth.invoke(null);
        System.err.println(o);
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(os);
        oos.writeObject(o);
        oos.close();
        
        ByteArrayInputStream is = new ByteArrayInputStream(os.toByteArray());
        ObjectInputStream ois = new ObjectInputStream(is);
        final Object read = ois.readObject();
        System.err.println(read);
        cls = Class.forName("com.redhat.ceylon.compiler.java.test.interop.javaSerializationCompare_");
        meth = cls.getMethod("javaSerializationCompare", Object.class, Object.class);
        meth.invoke(null, o, read);
    }
}