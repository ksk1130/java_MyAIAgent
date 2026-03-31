package org.example;

public class TF_B {
    public void run() {
        // Simulate call to TF_C
        // TF_C.java
        TF_C c = new TF_C();
        c.run();
        System.out.println("TF_B running");
    }
}
