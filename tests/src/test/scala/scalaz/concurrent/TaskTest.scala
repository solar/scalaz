package scalaz
package concurrent

import scalaz.scalacheck.ScalazProperties._
import scalaz.scalacheck.ScalazArbitrary._
import scalaz.std.AllInstances._
import org.scalacheck.Prop._

import java.util.concurrent.{Executors, TimeoutException}
import java.util.concurrent.atomic._
import org.scalacheck.Prop.forAll

object TaskTest extends SpecLite {

  val N = 10000
  val correct = (0 to N).sum
  val LM = Monad[List]; import LM.monadSyntax._; 
  val LT = Traverse[List]; import LT.traverseSyntax._

  // standard worst case scenario for trampolining - 
  // huge series of left associated binds
  def leftAssociatedBinds(seed: (=> Int) => Task[Int], 
                          cur: (=> Int) => Task[Int]): Task[Int] = 
    (0 to N).map(cur(_)).foldLeft(seed(0))(Task.taskInstance.lift2(_ + _))

  val options = List[(=> Int) => Task[Int]](n => Task.now(n), Task.delay _ , Task.apply _)
  val combinations = (options tuple options)

  "left associated binds" ! check {
    combinations.forall { case (seed, cur) => leftAssociatedBinds(seed, cur).run == correct }
  }

  "traverse-based map == sequential map" ! forAll { (xs: List[Int]) =>
    xs.map(_ + 1) == xs.traverse(x => Task(x + 1)).run
  }

  "gather-based map == sequential map" ! forAll { (xs: List[Int]) =>
    xs.map(_ + 1) == Nondeterminism[Task].gather(xs.map(x => Task(x + 1))).run
  }

  case object FailWhale extends RuntimeException {
    override def fillInStackTrace = this 
  }

  case object SadTrombone extends RuntimeException {
    override def fillInStackTrace = this
  }

  "catches exceptions" ! check {
    Task { Thread.sleep(10); throw FailWhale; 42 }.map(_ + 1).attemptRun ==
    -\/(FailWhale)
  }

  "catches exceptions in a mapped function" ! check {
    Task { Thread.sleep(10); 42 }.map(_ => throw FailWhale).attemptRun ==
    -\/(FailWhale)
  }

  "catches exceptions in a mapped function, created by delay" ! check {
    Task.delay { Thread.sleep(10); 42 }.map(_ => throw FailWhale).attemptRun ==
    -\/(FailWhale)
  }

  "catches exceptions in a mapped function, created with now" ! check {
    Task.now { Thread.sleep(10); 42 }.map(_ => throw FailWhale).attemptRun ==
    -\/(FailWhale)
  }

  "catches exceptions in a flatMapped function" ! check {
    Task { Thread.sleep(10); 42 }.flatMap(_ => throw FailWhale).attemptRun ==
    -\/(FailWhale)
  }

  "catches exceptions in a flatMapped function, created with delay" ! check {
    Task.delay { Thread.sleep(10); 42 }.flatMap(_ => throw FailWhale).attemptRun ==
    -\/(FailWhale)
  }

  "catches exceptions in a flatMapped function, created with now" ! check {
    Task.now { Thread.sleep(10); 42 }.flatMap(_ => throw FailWhale).attemptRun ==
    -\/(FailWhale)
  }

  "catches exceptions in parallel execution" ! forAll { (x: Int, y: Int) =>
    val t1 = Task { Thread.sleep(10); throw FailWhale; 42 }
    val t2 = Task { 43 }
    Nondeterminism[Task].both(t1, t2).attemptRun == -\/(FailWhale)
  }

  "handles exceptions in handle" ! {
    Task { Thread.sleep(10); throw FailWhale; 42 }.handle { case FailWhale => 84 }.attemptRun ==
      \/-(84)
  }

  "leaves unhandled exceptions alone in handle" ! {
    Task { Thread.sleep(10); throw FailWhale; 42 }.handle { case SadTrombone => 84 }.attemptRun ==
      -\/(FailWhale)
  }

  "catches exceptions thrown in handle" ! {
    Task { Thread.sleep(10); throw FailWhale; 42 }.handle { case FailWhale => throw SadTrombone }.attemptRun ==
      -\/(SadTrombone)
  }

  "handles exceptions in handleWith" ! {
    val foo =
    Task { Thread.sleep(10); throw FailWhale; 42 }.handleWith { case FailWhale => Task.delay(84) }.attemptRun ==
      \/-(84)
  }

  "leaves unhandled exceptions alone in handleWith" ! {
    Task { Thread.sleep(10); throw FailWhale; 42 }.handleWith { case SadTrombone => Task.delay(84) }.attemptRun ==
      -\/(FailWhale)
  }

  "catches exceptions thrown in handleWith" ! {
    Task { Thread.sleep(10); throw FailWhale; 42 }.handleWith { case FailWhale => Task.delay(throw SadTrombone) }.attemptRun ==
      -\/(SadTrombone)
  }


  "Nondeterminism[Task]" should {
    import scalaz.concurrent.Task._
    val es = Executors.newFixedThreadPool(1)


    "correctly process gatherUnordered for >1 tasks in non-blocking way" in {
      val t1 = fork(now(1))(es)
      val t2 = delay(7).flatMap(_=>fork(now(2))(es))
      val t3 = fork(now(3))(es)
      val t = fork(Task.gatherUnordered(Seq(t1,t2,t3)))(es)

      t.run.toSet must_== Set(1,2,3)
    }


    "correctly process gatherUnordered for 1 task in non-blocking way" in {
      val t1 = fork(now(1))(es)

      val t = fork(Task.gatherUnordered(Seq(t1)))(es)

      t.run.toSet must_== Set(1)
    }

    "correctly process gatherUnordered for empty seq of tasks in non-blocking way" in {
      val t = fork(Task.gatherUnordered(Seq()))(es)

      t.run.toSet must_== Set()
    }

    "early terminate once any of the tasks failed" in {
      import Thread._
      val ex = new RuntimeException("expected")
      
      val t1v = new AtomicInteger(0)
      val t3v = new AtomicInteger(0)

      val es3 = Executors.newFixedThreadPool(3)
      
      // NB: Task can only be interrupted in between steps (before the `map`)
      val t1 = fork { sleep(1000); now(()) }.map { _ => t1v.set(1) }
      val t2 = fork { now(throw ex) }
      val t3 = fork { sleep(1000); now(()) }.map { _ => t3v.set(3) }

      val t = fork(Task.gatherUnordered(Seq(t1,t2,t3), exceptionCancels = true))(es3)
      
      t.attemptRun match {
        case -\/(e) => e must_== ex 
      }
      
      t1v.get must_== 0
      t3v.get must_== 0
    }

    "early terminate once any of the tasks failed, and cancels execution" in {
      import Thread._
      val ex = new RuntimeException("expected")

      val t1v = new AtomicInteger(0)
      val t3v = new AtomicInteger(0)

      implicit val es3 = Executors.newFixedThreadPool(3)

      // NB: Task can only be interrupted in between steps (before the `map`)
      val t1 = fork { sleep(1000); now(()) }.map { _ => t1v.set(1) }
      val t2 = fork { sleep(100); now(throw ex) }
      val t3 = fork { sleep(1000); now(()) }.map { _ => t3v.set(3) }

      val t = fork(Task.gatherUnordered(Seq(t1,t2,t3), exceptionCancels = true))(es3)

      t.attemptRun mustMatch {
        case -\/(e) => e must_== ex; true
      }
      
      sleep(3000)

      t1v.get must_== 0
      t3v.get must_== 0
    }
  }
}

