package com.beeline.temporalmini;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class M implements T1, T2{
    @Override
    public void m() {


    }

    public static void main(String[] args) {
        List<Integer> arrayList = new ArrayList<>();
        List<Integer> linedList = new LinkedList<>();


        for (int i = 0; i < 1_000_000; i++) {
            arrayList.add(i); linedList.add(i);
        }

        time(arrayList);
        time(linedList);


    }


    public static void time(List<?> a){
        long start = System.nanoTime();

        for (var b : a){
//            System.out.println(b);
            var sd = b;
        }

        System.out.println("Time: " + (System.nanoTime() - start )/1000);
    }



}
