import * as maplibregl from "https://unpkg.com/maplibre-gl@6.10.0/dist/maplibre-gl.mjs";

const MOSCOW_CENTER = [37.6173, 55.7558];
const MOSCOW_ZOOM = 11.2;
const LOCATIONS = Array.isArray(window.VISION_LOCATIONS) ? window.VISION_LOCATIONS : [];

const app = document.getElementById("app");
const panelInner = document.getElementById("panelInner");
const filters = document.getElementById("filters");
const searchForm = document.getElementById("searchForm");
const searchInput = document.getElementById("searchInput");
const locationCount = document.getElementById("locationCount");
const mapHint = document.getElementById("mapHint");
const heroPhoto = document.getElementById("heroPhoto");
const photoCounter = document.getElementById("photoCounter");
const thumbs = document.getElementById("thumbs");
const locationSubtitle = document.getElementById("locationSubtitle");
const locationTitle = document.getElementById("locationTitle");
const coords = document.getElementById("coords");
const coordsSmall = document.getElementById("coordsSmall");
const district = document.getElementById("district");
const shotOn = document.getElementById("shotOn");
const shotWhen = document.getElementById("shotWhen");
const lens = document.getElementById("lens");
const notes = document.getElementById("notes");
const tags = document.getElementById("tags");
const favoriteButton = document.getElementById("favoriteButton");
const openMaps = document.getElementById("openMaps");
const fullscreenPhoto = document.getElementById("fullscreenPhoto");
const lightbox = document.getElementById("lightbox");
const lightboxPhoto = document.getElementById("lightboxPhoto");
const lightboxTitle = document.getElementById("lightboxTitle");
const lightboxCount = document.getElementById("lightboxCount");
const toast = document.getElementById("toast");

let activeFilter = "All";
let selectedLocation = LOCATIONS[0] || null;
let selectedPhoto = 0;
let toastTimer = null;
const markers = new Map();

const rasterStyle = {
  version: 8,
  sources: {
    osm: {
      type: "raster",
      tiles: ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
      tileSize: 256,
      attribution: "© OpenStreetMap contributors",
      maxzoom: 19
    }
  },
  layers: [
    {
      id: "osm",
      type: "raster",
      source: "osm",
      paint: {
        "raster-saturation": -1,
        "raster-contrast": 0.26,
        "raster-brightness-min": 0.02,
        "raster-brightness-max": 0.30,
        "raster-fade-duration": 120
      }
    }
  ]
};

const map = new maplibregl.Map({
  container: "map",
  style: rasterStyle,
  center: MOSCOW_CENTER,
  zoom: MOSCOW_ZOOM,
  minZoom: 9.5,
  maxZoom: 18.5,
  attributionControl: false,
  pitchWithRotate: false,
  dragRotate: false,
  touchPitch: false,
  antialias: true
});

function showToast(message) {
  toast.textContent = message;
  toast.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.remove("show"), 1400);
}

function formatCoords(location) {
  const [lng, lat] = location.coordinates;
  return lat.toFixed(6) + ", " + lng.toFixed(6);
}

function isSaved(id) {
  try {
    const saved = JSON.parse(localStorage.getItem("vision-photo-saved") || "[]");
    return saved.includes(id);
  } catch (_) {
    return false;
  }
}

function setSaved(id, state) {
  let saved = [];
  try { saved = JSON.parse(localStorage.getItem("vision-photo-saved") || "[]"); } catch (_) {}
  const set = new Set(saved);
  state ? set.add(id) : set.delete(id);
  localStorage.setItem("vision-photo-saved", JSON.stringify([...set]));
}

function setMediaBackground(node, url, fallbackIndex = 0) {
  node.style.backgroundImage = "";
  node.classList.remove("loaded");
  const tester = new Image();
  tester.onload = () => {
    node.style.backgroundImage = 'url("' + url + '")';
    node.classList.add("loaded");
    const fallback = node.querySelector(".photo-fallback");
    if (fallback) fallback.style.display = "none";
  };
  tester.onerror = () => {
    node.style.backgroundImage =
      fallbackIndex === 2
        ? "linear-gradient(145deg,#252525,#07090b 68%)"
        : "radial-gradient(circle at 45% 28%,#29434a,transparent 22%),linear-gradient(145deg,#111b20,#050708 70%)";
  };
  tester.src = url;
}

function renderPhoto(index, animate = true) {
  if (!selectedLocation) return;
  const count = selectedLocation.images.length;
  selectedPhoto = (index + count) % count;

  if (animate && heroPhoto.animate) {
    heroPhoto.animate(
      [{ opacity: .55, transform: "scale(1.015)" }, { opacity: 1, transform: "scale(1)" }],
      { duration: 360, easing: "cubic-bezier(.16,1,.3,1)" }
    );
  }

  setMediaBackground(heroPhoto, selectedLocation.images[selectedPhoto], selectedPhoto);
  photoCounter.textContent = (selectedPhoto + 1) + " / " + count;
  [...thumbs.children].forEach((node, i) => node.classList.toggle("active", i === selectedPhoto));

  if (lightbox.open) {
    setMediaBackground(lightboxPhoto, selectedLocation.images[selectedPhoto], selectedPhoto);
    lightboxCount.textContent = (selectedPhoto + 1) + " / " + count;
  }
}

function renderLocation(location, {fly = true} = {}) {
  if (!location) return;
  selectedLocation = location;
  selectedPhoto = 0;

  panelInner.classList.add("switching");

  setTimeout(() => {
    locationSubtitle.textContent = location.subtitle;
    locationTitle.textContent = location.title;
    const coordinateText = formatCoords(location);
    coords.textContent = coordinateText;
    coordsSmall.textContent = coordinateText;
    district.textContent = location.district;
    shotOn.textContent = location.shotOn;
    shotWhen.textContent = location.when;
    lens.textContent = location.lens;
    notes.textContent = location.notes;
    favoriteButton.classList.toggle("saved", isSaved(location.id));

    thumbs.replaceChildren();
    location.images.forEach((image, index) => {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "thumb media-frame" + (index === 0 ? " active" : "");
      button.setAttribute("aria-label", "Фото " + (index + 1));
      setMediaBackground(button, image, index);
      button.addEventListener("click", () => renderPhoto(index));
      thumbs.appendChild(button);
    });

    tags.replaceChildren();
    location.tags.forEach((tag, index) => {
      const chip = document.createElement("span");
      chip.className = "tag" + (index === 0 ? " primary" : "");
      chip.textContent = tag;
      tags.appendChild(chip);
    });

    setMediaBackground(heroPhoto, location.images[0], 0);
    photoCounter.textContent = "1 / " + location.images.length;

    markers.forEach((value, key) => value.element.classList.toggle("active", key === location.id));

    panelInner.classList.remove("switching");

    if (fly) {
      map.flyTo({
        center: location.coordinates,
        zoom: Math.max(map.getZoom(), 14.5),
        duration: 1050,
        essential: true
      });
    }
  }, 150);
}

function visibleLocations() {
  const query = searchInput.value.trim().toLowerCase();
  return LOCATIONS.filter(location => {
    const filterMatch = activeFilter === "All" || location.categories.includes(activeFilter) || location.tags.includes(activeFilter);
    if (!filterMatch) return false;
    if (!query) return true;
    const haystack = [
      location.title, location.subtitle, location.city, location.district,
      ...location.categories, ...location.tags
    ].join(" ").toLowerCase();
    return haystack.includes(query);
  });
}

function syncMarkers() {
  const visible = new Set(visibleLocations().map(location => location.id));
  markers.forEach((marker, id) => marker.element.style.display = visible.has(id) ? "" : "none");
  locationCount.textContent = visible.size;

  if (visible.size === 1) {
    const only = LOCATIONS.find(location => visible.has(location.id));
    if (only && selectedLocation?.id !== only.id) renderLocation(only, {fly:false});
  }
}

function buildMarkers() {
  LOCATIONS.forEach(location => {
    const element = document.createElement("button");
    element.type = "button";
    element.className = "location-marker";
    element.setAttribute("aria-label", location.title);

    element.addEventListener("click", event => {
      event.stopPropagation();
      renderLocation(location);
    });

    const marker = new maplibregl.Marker({element, anchor:"center"})
      .setLngLat(location.coordinates)
      .addTo(map);

    markers.set(location.id, {marker, element});
  });
}

map.on("load", () => {
  buildMarkers();
  if (selectedLocation) {
    renderLocation(selectedLocation, {fly:false});
    setTimeout(() => {
      map.easeTo({center:selectedLocation.coordinates, zoom:12.2, duration:900});
    }, 280);
  }
});

document.getElementById("zoomIn").addEventListener("click", () => map.zoomIn({duration:350}));
document.getElementById("zoomOut").addEventListener("click", () => map.zoomOut({duration:350}));
document.getElementById("northButton").addEventListener("click", () => map.resetNorth({duration:300}));
document.getElementById("resetMap").addEventListener("click", () => map.flyTo({center:MOSCOW_CENTER, zoom:MOSCOW_ZOOM, duration:900}));
mapHint.addEventListener("click", () => selectedLocation && renderLocation(selectedLocation));

filters.addEventListener("click", event => {
  const button = event.target.closest("[data-filter]");
  if (!button) return;
  activeFilter = button.dataset.filter;
  [...filters.querySelectorAll(".filter")].forEach(node => node.classList.toggle("active", node === button));
  syncMarkers();
});

searchForm.addEventListener("submit", event => {
  event.preventDefault();
  syncMarkers();
  const visible = visibleLocations();
  if (!visible.length) showToast("Nothing found");
  else if (visible[0]) renderLocation(visible[0]);
});

searchInput.addEventListener("input", syncMarkers);

window.addEventListener("keydown", event => {
  const editable = /input|textarea/i.test(document.activeElement?.tagName || "");
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
    event.preventDefault();
    searchInput.focus();
    searchInput.select();
  }
  if (!editable && event.key === "Escape" && lightbox.open) lightbox.close();
  if (!editable && event.key === "ArrowLeft" && lightbox.open) renderPhoto(selectedPhoto - 1);
  if (!editable && event.key === "ArrowRight" && lightbox.open) renderPhoto(selectedPhoto + 1);
});

favoriteButton.addEventListener("click", () => {
  if (!selectedLocation) return;
  const next = !isSaved(selectedLocation.id);
  setSaved(selectedLocation.id, next);
  favoriteButton.classList.toggle("saved", next);
  showToast(next ? "Saved" : "Removed from saved");
});

document.getElementById("savedNav").addEventListener("click", () => {
  const saved = LOCATIONS.filter(location => isSaved(location.id));
  if (!saved.length) return showToast("No saved locations yet");
  renderLocation(saved[0]);
});

openMaps.addEventListener("click", () => {
  if (!selectedLocation) return;
  const [lng, lat] = selectedLocation.coordinates;
  window.open("https://www.openstreetmap.org/?mlat=" + lat + "&mlon=" + lng + "#map=18/" + lat + "/" + lng, "_blank", "noopener,noreferrer");
});

fullscreenPhoto.addEventListener("click", () => {
  if (!selectedLocation) return;
  lightboxTitle.textContent = selectedLocation.title;
  lightboxCount.textContent = (selectedPhoto + 1) + " / " + selectedLocation.images.length;
  setMediaBackground(lightboxPhoto, selectedLocation.images[selectedPhoto], selectedPhoto);
  lightbox.showModal();
});

document.getElementById("lightboxClose").addEventListener("click", () => lightbox.close());
document.getElementById("lightboxPrev").addEventListener("click", () => renderPhoto(selectedPhoto - 1));
document.getElementById("lightboxNext").addEventListener("click", () => renderPhoto(selectedPhoto + 1));
lightbox.addEventListener("click", event => { if (event.target === lightbox) lightbox.close(); });

document.getElementById("helpButton").addEventListener("click", () => {
  showToast("Click a map point → browse photos → open location");
});

document.querySelectorAll("[data-nav]").forEach(button => {
  button.addEventListener("click", () => {
    document.querySelectorAll(".rail-button").forEach(node => node.classList.remove("active"));
    button.classList.add("active");
    if (button.dataset.nav !== "map") showToast("This section will grow with new locations");
  });
});

window.addEventListener("resize", () => map.resize());
syncMarkers();
