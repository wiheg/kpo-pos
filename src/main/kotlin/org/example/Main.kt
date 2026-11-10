package org.example

import kotlin.reflect.KClass

// domain
data class Score(val value: Double) {
    init {
        require(value >= 0.0) { "Балл не может быть отрицательным: $value" }
    }
}

data class Student(val id: String, val name: String, val group: String = "") {
    init {
        require(id.isNotBlank() && name.isNotBlank()) { "ID и имя не могут быть пустыми" }
    }
}

data class Criterion(val id: String, val name: String, val maxScore: Double, val weight: Double = 1.0) {
    init {
        require(id.isNotBlank() && name.isNotBlank()) { "ID и название не могут быть пустыми" }
        require(maxScore > 0.0 && weight > 0.0) { "Макс. балл и вес должны быть > 0" }
    }
}

data class Evaluation(
    val id: String,
    val studentId: String,
    val criterionId: String,
    val score: Score,
    val comment: String = ""
)

data class FinalGrade(val student: Student, val total: Double, val maxPossible: Double)

interface Repository<T> {
    fun save(entity: T)
    fun findById(id: String): T?
    fun findAll(): List<T>
    fun delete(id: String): Boolean
}

interface StudentRepo : Repository<Student>
interface CriterionRepo : Repository<Criterion>
interface EvaluationRepo : Repository<Evaluation>

// factory
class EvaluationFactory(
    private val students: StudentRepo,
    private val criteria: CriterionRepo
) {
    fun create(id: String, studentId: String, criterionId: String, score: Double, comment: String = ""): Evaluation {
        students.findById(studentId) ?: error("Студент '$studentId' не найден")
        val crit = criteria.findById(criterionId) ?: error("Критерий '$criterionId' отсутствует в рубрикаторе")
        require(score in 0.0..crit.maxScore) { "Балл $score вне диапазона [0.0, ${crit.maxScore}]" }
        return Evaluation(id, studentId, criterionId, Score(score), comment)
    }
}

// strategy
interface GradingStrategy {
    fun calculate(evals: List<Evaluation>, criteria: List<Criterion>): Double
}

class WeightedStrategy(private val scale: Double = 10.0) : GradingStrategy {
    override fun calculate(evals: List<Evaluation>, criteria: List<Criterion>): Double {
        val totalWeight = criteria.sumOf { it.weight }
        if (totalWeight == 0.0) return 0.0
        val map = evals.associateBy { it.criterionId }
        val sum = criteria.sumOf { (map[it.id]?.score?.value ?: 0.0) / it.maxScore * it.weight }
        return (sum / totalWeight) * scale
    }
}

class DirectSumStrategy : GradingStrategy {
    override fun calculate(evals: List<Evaluation>, criteria: List<Criterion>): Double {
        val map = evals.associateBy { it.criterionId }
        return criteria.sumOf { map[it.id]?.score?.value ?: 0.0 }
    }
}

// ddd
class FinalGradeCalculator(private val defaultStrategy: GradingStrategy = WeightedStrategy()) {
    fun calculate(
        student: Student,
        evals: List<Evaluation>,
        criteria: List<Criterion>,
        strategy: GradingStrategy? = null
    ): FinalGrade {
        val s = strategy ?: defaultStrategy
        val total = s.calculate(evals, criteria)
        val max = if (s is WeightedStrategy) 10.0 else criteria.sumOf { it.maxScore }
        return FinalGrade(student, total, max)
    }
}

// application
// facade
class PosFacade(
    val students: StudentRepo,
    val criteria: CriterionRepo,
    val evals: EvaluationRepo,
    val factory: EvaluationFactory,
    val calculator: FinalGradeCalculator
) {
    fun addStudent(id: String, name: String, group: String = "") = Student(id, name, group).also { students.save(it) }
    fun addCriterion(id: String, name: String, max: Double, weight: Double = 1.0) =
        Criterion(id, name, max, weight).also { criteria.save(it) }

    fun evaluate(id: String, studentId: String, critId: String, score: Double, comment: String = "") =
        factory.create(id, studentId, critId, score, comment).also { evals.save(it) }

    fun updateEvaluation(id: String, newScore: Double, comment: String? = null): Evaluation {
        val old = evals.findById(id) ?: error("Оценка '$id' не найдена")
        return factory.create(old.id, old.studentId, old.criterionId, newScore, comment ?: old.comment)
            .also { evals.save(it) }
    }

    fun removeEvaluation(id: String) = evals.delete(id)
    fun getEvaluations(studentId: String? = null) =
        evals.findAll().filter { studentId == null || it.studentId == studentId }

    fun calculateGrade(studentId: String, strategy: GradingStrategy? = null): FinalGrade {
        val student = students.findById(studentId) ?: error("Студент '$studentId' не найден")
        return calculator.calculate(student, getEvaluations(studentId), criteria.findAll(), strategy)
    }

    fun calculateAll(strategy: GradingStrategy? = null) =
        students.findAll().map { calculateGrade(it.id, strategy) }

    fun export(exporter: GradeReportExporter, strategy: GradingStrategy? = null) =
        exporter.export(calculateAll(strategy))
}

// infrastructure
open class InMemoryRepo<T>(private val idSelector: (T) -> String) : Repository<T> {
    private val map = mutableMapOf<String, T>()
    override fun save(entity: T) {
        map[idSelector(entity)] = entity
    }

    override fun findById(id: String) = map[id]
    override fun findAll(): List<T> = map.values.toList()
    override fun delete(id: String) = map.remove(id) != null
}

class InMemoryStudentRepo : InMemoryRepo<Student>({ it.id }), StudentRepo
class InMemoryCriterionRepo : InMemoryRepo<Criterion>({ it.id }), CriterionRepo
class InMemoryEvaluationRepo : InMemoryRepo<Evaluation>({ it.id }), EvaluationRepo

// template method
abstract class GradeReportExporter {
    fun export(grades: List<FinalGrade>): String = buildString {
        appendLine(header())
        grades.forEach { appendLine(row(it)) }
        append(footer(grades))
    }

    protected abstract fun header(): String
    protected abstract fun row(grade: FinalGrade): String
    protected open fun footer(grades: List<FinalGrade>): String = ""
}

class CsvReportExporter : GradeReportExporter() {
    override fun header() = "student_id,name,score,max"
    override fun row(grade: FinalGrade) = "${grade.student.id},\"${grade.student.name}\",${
        "%.2f".format(
            java.util.Locale.US,
            grade.total
        )
    },${"%.2f".format(java.util.Locale.US, grade.maxPossible)}"

    override fun footer(grades: List<FinalGrade>) = "# total=${grades.size}"
}

class DiContainer {
    val bindings = mutableMapOf<KClass<*>, () -> Any>()
    inline fun <reified T : Any> registerTransient(noinline f: DiContainer.() -> T) {
        bindings[T::class] = { f() }
    }

    inline fun <reified T : Any> registerSingleton(noinline f: DiContainer.() -> T) {
        val inst by lazy { f() }
        bindings[T::class] = { inst }
    }

    inline fun <reified T : Any> resolve(): T =
        (bindings[T::class]?.invoke() ?: error("Зависимость ${T::class.simpleName} не найдена")) as T
}

fun configureContainer(strategyOverride: GradingStrategy? = null) = DiContainer().apply {
    registerSingleton<StudentRepo> { InMemoryStudentRepo() }
    registerSingleton<CriterionRepo> { InMemoryCriterionRepo() }
    registerSingleton<EvaluationRepo> { InMemoryEvaluationRepo() }
    registerTransient<GradingStrategy> { strategyOverride ?: WeightedStrategy() }
    registerTransient { EvaluationFactory(resolve(), resolve()) }
    registerTransient { FinalGradeCalculator(resolve()) }
    registerTransient { PosFacade(resolve(), resolve(), resolve(), resolve(), resolve()) }
}


fun main() {
    System.setOut(java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8))
    val pos = configureContainer().resolve<PosFacade>()

    println("\n[1] Студенты и рубрикатор:")
    pos.addStudent("S-01", "Алексей Иванов", "БПИ-231")
    pos.addStudent("S-02", "Мария Смирнова", "БПИ-231")
    pos.students.findAll().forEach { println("  [Студент] ${it.id}: ${it.name}") }

    pos.addCriterion("CR-ATT", "Активность", max = 10.0, weight = 0.3)
    pos.addCriterion("CR-HW", "Домашние задания", max = 100.0, weight = 0.7)
    pos.criteria.findAll()
        .forEach { println("  [Критерий] ${it.id}: ${it.name} (макс: ${it.maxScore}, вес: ${it.weight})") }

    println("\n[2] Оценивание:")
    pos.evaluate("EV-1", "S-01", "CR-ATT", 9.0, "Активен на семинарах")
    pos.evaluate("EV-2", "S-01", "CR-HW", 95.0, "Отличный код")
    pos.evaluate("EV-3", "S-02", "CR-ATT", 8.0)
    pos.evaluate("EV-4", "S-02", "CR-HW", 70.0)
    pos.getEvaluations()
        .forEach { println("  [Оценка ${it.id}] Студент ${it.studentId}: ${it.score.value} по ${it.criterionId}") }

    println("\n[3] Защита инвариантов домена:")
    runCatching { pos.evaluate("E-ERR1", "S-01", "CR-HW", 120.0) }
        .onFailure { println("  [ОТКЛОНЕНО] Превышение max_score: ${it.message}") }
    runCatching { pos.evaluate("E-ERR2", "S-01", "UNKNOWN", 5.0) }
        .onFailure { println("  [ОТКЛОНЕНО] Несуществующий критерий: ${it.message}") }

    println("\n[4] Исправление и отмена оценки:")
    val updated = pos.updateEvaluation("EV-3", 10.0, "Бонус за активность")
    println("  [ИСПРАВЛЕНО] Оценка ${updated.id}: новый балл ${updated.score.value}")
    println("  [ОТМЕНЕНО] Оценка EV-4 удалена: ${pos.removeEvaluation("EV-4")}")

    println("\n[5] Экспорт в CSV:")
    println(pos.export(CsvReportExporter()))

    println("\n[6] Итог (Прямая сумма баллов):")
    pos.calculateAll(DirectSumStrategy()).forEach {
        println("  ${it.student.name}: ${"%.1f".format(it.total)} из ${it.maxPossible}")
    }

    println("\n[7] Подмена стратегии через DI:")
    val customPos = configureContainer(object : GradingStrategy {
        override fun calculate(evals: List<Evaluation>, criteria: List<Criterion>) = 10.0
    }).resolve<PosFacade>()
    customPos.addStudent("S-01", "Алексей Иванов")
    println("  Результат тестовой стратегии: ${customPos.calculateGrade("S-01").total}")
}
