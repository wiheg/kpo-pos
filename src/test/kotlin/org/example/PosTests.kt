package org.example

import kotlin.test.*

class PosTests {
    private val students = InMemoryStudentRepo()
    private val criteria = InMemoryCriterionRepo()
    private val evals = InMemoryEvaluationRepo()
    private val factory = EvaluationFactory(students, criteria)
    private val calculator = FinalGradeCalculator()
    private val facade = PosFacade(students, criteria, evals, factory, calculator)

    @BeforeTest
    fun setUp() {
        facade.addStudent("S1", "Иван Иванов")
        facade.addCriterion("C1", "ДЗ", 100.0, 0.7)
        facade.addCriterion("C2", "Активность", 10.0, 0.3)
    }

    @Test
    fun `domain invariants guard negative scores and invalid data`() {
        assertFailsWith<IllegalArgumentException> { Score(-1.0) }
        assertFailsWith<IllegalArgumentException> { Student("", "Имя") }
        assertFailsWith<IllegalArgumentException> { Criterion("C", "Название", -5.0) }
    }

    @Test
    fun `factory rejects out of bounds scores and missing criteria`() {
        assertFailsWith<IllegalArgumentException> { factory.create("E1", "S1", "C1", 105.0) }
        assertFailsWith<IllegalStateException> { factory.create("E2", "S1", "UNKNOWN", 5.0) }
        assertFailsWith<IllegalStateException> { factory.create("E3", "UNKNOWN_STUDENT", "C1", 50.0) }
    }

    @Test
    fun `facade crud operations for evaluations`() {
        val eval = facade.evaluate("E1", "S1", "C1", 80.0, "коммент")
        assertEquals(80.0, eval.score.value)
        assertEquals("коммент", eval.comment)

        val updated = facade.updateEvaluation("E1", 90.0, "обновлено")
        assertEquals(90.0, updated.score.value)
        assertEquals(1, facade.getEvaluations("S1").size)

        assertTrue(facade.removeEvaluation("E1"))
        assertTrue(facade.getEvaluations("S1").isEmpty())
    }

    @Test
    fun `grade calculation with strategies`() {
        facade.evaluate("E1", "S1", "C1", 80.0)
        facade.evaluate("E2", "S1", "C2", 10.0)

        val weighted = facade.calculateGrade("S1")
        assertEquals(8.6, weighted.total, 0.001)

        val direct = facade.calculateGrade("S1", DirectSumStrategy())
        assertEquals(90.0, direct.total, 0.001)
    }

    @Test
    fun `exporters template method`() {
        facade.evaluate("E1", "S1", "C1", 100.0)
        facade.evaluate("E2", "S1", "C2", 10.0)

        val csv = facade.export(CsvReportExporter())
        assertTrue(csv.contains("student_id,name,score"))
        assertTrue(csv.contains("S1,\"Иван Иванов\",10.00,10.00"))
    }

    @Test
    fun `di container resolves singletons transients and stubs`() {
        val container = DiContainer().apply {
            registerSingleton<StudentRepo> { InMemoryStudentRepo() }
            registerTransient<GradingStrategy> { DirectSumStrategy() }
        }
        assertSame(container.resolve<StudentRepo>(), container.resolve<StudentRepo>())
        assertNotSame(container.resolve<GradingStrategy>(), container.resolve<GradingStrategy>())

        val stubContainer = configureContainer(strategyOverride = object : GradingStrategy {
            override fun calculate(evals: List<Evaluation>, criteria: List<Criterion>) = 5.55
        })
        val stubFacade = stubContainer.resolve<PosFacade>()
        stubFacade.addStudent("S-STUB", "Тест")
        assertEquals(5.55, stubFacade.calculateGrade("S-STUB").total, 0.001)
    }

    @Test
    fun `main execution smoke test`() {
        main()
    }
}
