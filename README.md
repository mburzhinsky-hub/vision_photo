# Vision Photo

Интерактивный desktop-first гид по фотолокациям Москвы.

## MVP

- полноэкранная карта Москвы;
- MapLibre GL JS + OpenStreetMap raster tiles;
- тёмная минималистичная картографическая тема;
- собственные Nothing-style markers вместо стандартных pin;
- поиск и фильтры;
- detail panel с галереей;
- fullscreen photo viewer;
- metadata / notes / tags;
- сохранение локаций в localStorage;
- первая точка: **Moscow City · Viewpoint 01** — `55.747975, 37.540821`;
- GitHub Pages workflow.

## Фотографии первой точки

Интерфейс уже ожидает файлы:

```
assets/moscow-city-01.jpg
assets/moscow-city-02.jpg
assets/moscow-city-03.jpg
```

Если файлов нет, приложение использует стилизованный fallback и не ломает интерфейс.

EXIF в исходных трёх фотографиях отсутствует, поэтому camera / lens / date пока показаны как Unknown / EXIF unavailable.

## Новые локации

Все данные находятся в `data/locations.js`. Добавление точки не требует менять map logic:

```js
{
  id: "unique-id",
  title: "Location name",
  coordinates: [longitude, latitude],
  categories: ["Architecture"],
  tags: ["Architecture", "Minimal"],
  images: ["./assets/photo-1.jpg"]
}
```

## Local preview

Из корня репозитория:

```bash
python -m http.server 8080
```

Открыть `http://localhost:8080`.

## Map

Map rendering: MapLibre GL JS.  
Basemap data / tiles: OpenStreetMap.  
Attribution is shown directly in the UI.
