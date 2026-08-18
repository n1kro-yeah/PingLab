# Как внести вклад

Проект небольшой, поэтому правила простые.

## Баги и предложения

Заводите [issue](https://github.com/n1kro-yeah/PingLab/issues) и укажите:

- версию приложения и способ установки — release или debug APK;
- модель устройства и версию Android;
- тип подключения: Wi-Fi или мобильный интернет, включён ли VPN — большая часть проблем связана именно с сетью;
- что делали, что ожидали увидеть и что произошло;
- адрес и протокол проверяемого хоста, если баг сетевой;
- скриншот или лог, если есть.

## Окружение

- JDK 17
- Android SDK с API 35
- Android Studio Ladybug или новее; сам Gradle подтянется через wrapper

```bash
git clone https://github.com/n1kro-yeah/PingLab.git
cd PingLab
./gradlew :app:assembleDebug
```

## Перед pull request

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintRelease
```

Оба прогона должны быть зелёными — CI выполняет ровно их. Новая логика в `domain/` и `core/` приходит вместе с юнит-тестами: эти слои не зависят от Android и проверяются обычным JUnit.

## Стиль

- Kotlin official code style, форматирование — как в соседних файлах.
- Интерфейс только на Compose и Material 3. Никаких XML-layout и хардкода цветов: значения берутся из `MaterialTheme.colorScheme`.
- Пользовательские строки живут в `res/values/strings.xml` и `res/values-ru/strings.xml`, а не в коде.
- Сеть не трогает главный поток: пробы выполняются в корутинах на `Dispatchers.IO`.
- Сообщения коммитов короткие, в повелительном наклонении, на английском: `Fix the port scanner verdict for filtered ports`.

## Ветки

Работа идёт в ветках вида `feature/<название>`, pull request открывается в `main`. Каждый push запускает CI, а зелёная сборка сама прикладывает оба APK к релизу `v<versionName>`.

## Лицензия вклада

Отправляя pull request, вы соглашаетесь, что ваш код распространяется на условиях [лицензии MIT](LICENSE).
