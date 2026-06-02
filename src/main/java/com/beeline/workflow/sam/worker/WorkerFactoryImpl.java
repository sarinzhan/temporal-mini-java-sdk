package com.beeline.workflow.sam.worker;

import com.beeline.workflow.sam.api.Worker;

import java.util.ArrayList;
import java.util.List;

public class  WorkerFactoryImpl {
    private List<Worker> workerList = new ArrayList<>();


    private WorkerFactoryImpl(){}

    public static WorkerFactoryImpl workerFactory(){
       return new WorkerFactoryImpl();
    }

    public Worker newWorker(String taskQueue){
        Worker worker = new WorkerImpl();
        worker.init(taskQueue);
        workerList.add(worker);
        return worker;
    }

    public void start(){
        for(Worker worker : workerList){
            worker.start();
        }
    }
}
