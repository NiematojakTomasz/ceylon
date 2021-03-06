/*
 * Copyright Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the authors tag. All rights reserved.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU General Public License version 2.
 * 
 * This particular file is subject to the "Classpath" exception as provided in the 
 * LICENSE file that accompanied this code.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License,
 * along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */
@noanno
class AttributeRef<T>(T t) 
        given T satisfies Object {
    shared String val = "val";
    shared T typeParam = t;
    
    shared void simple(AttributeRef<String> other) {
        String(AttributeRef<T>) valRef = AttributeRef<T>.val;
        assert("val" == valRef(this));
        
        String(AttributeRef<String>) valRef2 = AttributeRef<String>.val;
        assert("val" == valRef2(other));
        
        T(AttributeRef<T>) typeParamRef = AttributeRef<T>.typeParam;
        assert(t == typeParamRef(this));
        
        String(AttributeRef<String>) stringTypeParamRef = AttributeRef<String>.typeParam;
        assert("foo" == stringTypeParamRef(other));
    }
    
    
    shared void assortedLanguage() {
        Boolean(Object)(Object) objectEqualsRef = Object.equals;
        assert(objectEqualsRef(this)(this));
        assert(!objectEqualsRef(this)(""));
        Boolean(Object)(String) stringEqualsRef = String.equals;
        assert(stringEqualsRef("")(""));
        assert(!stringEqualsRef("")(this));
        Integer(Integer)(Integer) integerPlus = Integer.plus;
        assert(2 == integerPlus(1)(1));
        assert(1 == integerPlus(0)(1));
        String(String)(String) stringPlus = String.plus;
        assert("foobar" == stringPlus("foo")("bar"));
        assert(stringEqualsRef("foobar")(stringPlus("foo")("bar")));
    }
 
    // TODO Type parameters
    // TODO Defaulted parameters
    
    shared class Inner(String s) {
        shared String bar => "Inner(``s``)";
    }
    shared void innerClass() {
        String(AttributeRef<T>.Inner) innerRef = AttributeRef<T>.Inner.bar;
        assert("Inner(bar)" == innerRef(Inner("bar")));
    }
}

void attributeRef() {
    value ar = AttributeRef<String>("foo");
    ar.simple(ar);
    ar.assortedLanguage();
    ar.innerClass();
}