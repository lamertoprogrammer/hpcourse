package csc.parallel.server;

import communication.Protocol.Task;
import javafx.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * @author Andrey Kokorev
 *         Created on 13.04.2016.
 */
public class TaskSolver
{
    private final Logger logger = LoggerFactory.getLogger(TaskSolver.class);
    private final Object guard = new Object();
    private int maxThreads;
    private AtomicInteger availableThreads;
    private AtomicInteger threadCounter;
    private final Map<Integer, TaskHolder> tasks;

    public int getMaxThreads()
    {
        return maxThreads;
    }

    public TaskSolver(Map<Integer, TaskHolder> tasks)
    {
        this.tasks = tasks;
        this.maxThreads = Runtime.getRuntime().availableProcessors();
        this.availableThreads = new AtomicInteger(this.maxThreads);
        this.threadCounter = new AtomicInteger();
    }

    public void solveTask(TaskHolder holder)
    {
        Task task = holder.getTask();
        new Thread(() -> {
            int selfId = threadCounter.incrementAndGet();
            // firstly wait for all dependencies
            Map<String, Long> vals = null;
            try
            {
                vals = getValues(task);
            } catch (InterruptedException e)
            {
                logger.error("Interrupted {} solving {} while waiting for dependencies", selfId, holder.getId());
            }

            // here we are ready to solve
            int avail = 0;
            synchronized (guard)
            {
                try
                {
                    do
                    {
                        while((avail = availableThreads.get()) == 0)
                            guard.wait();
                        //try to occupy one slot
                    } while (!availableThreads.compareAndSet(avail, avail - 1));
                } catch (InterruptedException e)
                {
                    logger.info("Solver {} of task {} interrupted", selfId, holder.getId());
                    return;
                }
            }

            holder.setResult(compute(
                    vals.get("a"),
                    vals.get("b"),
                    vals.get("p"),
                    vals.get("m"),
                    task.getN()
            ));
            // Notify all dependant tasks and subscribers
            synchronized (holder.lock)
            {
                holder.lock.notifyAll();
            }

            synchronized (guard)
            {
                do
                {
                    avail = availableThreads.get();
                } while (!availableThreads.compareAndSet(avail, avail + 1));

                // free one another solver
                guard.notify();
            }
        });
    }

    private Map<String, Long> getValues(Task task) throws InterruptedException
    {
        Map<String, Long> result = new HashMap<>();

        List<Pair<String, Task.Param>> params = new ArrayList<>();
        params.add(new Pair<String, Task.Param>("a", task.getA()));
        params.add(new Pair<String, Task.Param>("b", task.getB()));
        params.add(new Pair<String, Task.Param>("p", task.getP()));
        params.add(new Pair<String, Task.Param>("m", task.getM()));

        List<Pair<String, Task.Param>> notAvaliable = new ArrayList<>();

        for(Pair<String, Task.Param> p : params)
        {
            if(p.getValue().hasValue())
                result.put(p.getKey(), p.getValue().getValue());
            else
                notAvaliable.add(p);
        }


        List<Pair<String, TaskHolder>> waitHolders = new ArrayList<>();
        synchronized (tasks)
        {
            waitHolders.addAll(notAvaliable.stream().map(p -> new Pair<>(
                    p.getKey(),
                    tasks.get(p.getValue().getDependentTaskId())
            )).collect(Collectors.toList()));
        }

        for(Pair<String, TaskHolder> p : waitHolders)
        {
            synchronized (p.getValue().lock)
            {
                while (!p.getValue().isDone())
                    p.getValue().lock.wait();
            }
            result.put(p.getKey(), p.getValue().getResult());
        }

        return result;
    }

    private long compute(long a, long b, long p, long m, long n)
    {
        while (n-- > 0)
        {
            b = (a * p + b) % m;
            a = b;
        }
        return a;
    }
}

