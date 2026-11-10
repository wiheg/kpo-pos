# ПОС — Домашнее задание №2 (КПО)

## Запуск

* Запуск приложения: `./gradlew run`
* Запуск тестов: `./gradlew test`

## Архитектура и слои

Ports and Adapters: зависимости внутрь (`Infrastructure` → `Application` → `Domain`)

* Domain: `Student`, `Criterion`, `Evaluation` (сущности), `Score`, `FinalGrade` (VO), `Repository<T>` (порт),
  `EvaluationFactory` (фабрика), `FinalGradeCalculator` (сервис + стратегии `WeightedStrategy`, `DirectSumStrategy`)
* Application: `PosFacade` — сценарии CRUD, расчёта и экспорта
* Infrastructure: `InMemoryRepo<T>`, `GradeReportExporter` (CSV/таблица), `DiContainer`, `Main.kt`

## DDD

* Ubiquitous Language: студент (`Student`), критерий рубрикатора (`Criterion`), оценка (`Evaluation`), балл (`Score`),
  итог (`FinalGrade`)
* Entity и Value Object: `Student`, `Criterion`, `Evaluation` — сущности с уникальным ID. `Score` и `FinalGrade` —
  объекты-значения
* Инварианты: балл критерия $\ge 0$, параметры критерия $> 0$, оценка не может превышать `maxScore` и ставится
  только по существующему критерию
* Repository: `Repository<T>` объявлен в домене, реализация изолирована в инфраструктуре
* Domain Service: `FinalGradeCalculator` считает итог по рубрикатору и набору оценок без привязки к конкретной
  сущности

## Паттерны GoF

* Порождающий: Factory (`EvaluationFactory`)
* Структурный: Facade (`PosFacade`)
* Поведенческий: Strategy (`GradingStrategy`)
* Поведенческий: Template Method (`GradeReportExporter`)

## Роль ИИ:

ИИ использовался для приведения консольного вывода в `main` и текста в `readme` к более красивому формату, а также
написания тестов
   
