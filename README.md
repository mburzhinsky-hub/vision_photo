# Vision Photo

Интерактивный desktop-first гид по фотолокациям Москвы.

## Сейчас реализовано

- тёмная минималистичная карта Москвы на MapLibre GL JS;
- OpenStreetMap tiles без обязательного API-ключа;
- Nothing-style маркеры и плавный fly-to;
- первая точка: Moscow City · Viewpoint 01 — 55.747975, 37.540821;
- поиск и фильтры;
- правая карточка локации;
- галерея из трёх кадров и fullscreen lightbox;
- сохранение локации в localStorage;
- Open in Maps;
- graceful fallback, если фото ещё не загружены;
- desktop responsive layout и анимации переходов.

## Фото первой локации

Положи исходные изображения в папку `assets/` с именами:

- `moscow-city-01.jpg`
- `moscow-city-02.jpg`
- `moscow-city-03.jpg`

Приложение уже ссылается на эти пути в `data/locations.js`.

## GitHub Pages

Репозиторий настроен на обычный GitHub Pages deploy из ветки `main`.
Отдельный Actions workflow не нужен — GitHub сам пересобирает Pages после каждого push.

Адрес:

`https://mburzhinsky-hub.github.io/vision_photo/`

## Структура

- `index.html` — интерфейс;
- `styles.css` — дизайн и адаптация;
- `app.js` — карта, маркеры, фильтры, галерея и интерактивность;
- `data/locations.js` — данные фототочек;
- `assets/` — фотографии локаций.

## Добавление новой точки

Добавь объект в `data/locations.js` с координатами в формате:

```js
coordinates: [longitude, latitude]
```

и положи изображения в `assets/`.
