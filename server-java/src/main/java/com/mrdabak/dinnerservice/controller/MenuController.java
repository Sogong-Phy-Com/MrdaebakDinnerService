package com.mrdabak.dinnerservice.controller;

import com.mrdabak.dinnerservice.model.DinnerMenuItem;
import com.mrdabak.dinnerservice.model.DinnerType;
import com.mrdabak.dinnerservice.model.MenuItem;
import com.mrdabak.dinnerservice.repository.DinnerMenuItemRepository;
import com.mrdabak.dinnerservice.repository.DinnerTypeRepository;
import com.mrdabak.dinnerservice.repository.MenuItemRepository;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/menu")
public class MenuController {

    private final DinnerTypeRepository dinnerTypeRepository;
    private final MenuItemRepository menuItemRepository;
    private final DinnerMenuItemRepository dinnerMenuItemRepository;

    public MenuController(DinnerTypeRepository dinnerTypeRepository, MenuItemRepository menuItemRepository,
                          DinnerMenuItemRepository dinnerMenuItemRepository) {
        this.dinnerTypeRepository = dinnerTypeRepository;
        this.menuItemRepository = menuItemRepository;
        this.dinnerMenuItemRepository = dinnerMenuItemRepository;
    }

    @GetMapping("/dinners")
    public List<Map<String, Object>> getDinners() {
        List<DinnerType> dinners = dinnerTypeRepository.findAll();
        return dinners.stream().map(dinner -> {
            Map<String, Object> dinnerMap = new HashMap<>();
            dinnerMap.put("id", dinner.getId());
            dinnerMap.put("name", dinner.getName());
            dinnerMap.put("name_en", dinner.getNameEn());
            dinnerMap.put("base_price", dinner.getBasePrice());
            dinnerMap.put("description", dinner.getDescription());

            List<DinnerMenuItem> dinnerMenuItems = dinnerMenuItemRepository.findByDinnerTypeId(dinner.getId());
            List<Map<String, Object>> menuItems = dinnerMenuItems.stream().map(dmi -> {
                MenuItem menuItem = menuItemRepository.findById(dmi.getMenuItemId()).orElse(null);
                if (menuItem == null) return null;
                Map<String, Object> itemMap = new HashMap<>();
                itemMap.put("id", menuItem.getId());
                itemMap.put("name", menuItem.getName());
                itemMap.put("name_en", menuItem.getNameEn());
                itemMap.put("price", menuItem.getPrice());
                itemMap.put("category", menuItem.getCategory());
                itemMap.put("quantity", dmi.getQuantity());
                return itemMap;
            }).filter(item -> item != null).collect(Collectors.toList());
            dinnerMap.put("menu_items", menuItems);
            return dinnerMap;
        }).collect(Collectors.toList());
    }

    @GetMapping("/items")
    public List<MenuItem> getMenuItems() {
        return menuItemRepository.findAll();
    }

    @GetMapping("/serving-styles")
    public List<Map<String, Object>> getServingStyles() {
        return List.of(
                Map.of("name", "simple", "name_ko", "심플", "price_multiplier", 1.0,
                        "description", "플라스틱 접시와 플라스틱 컵, 종이 냅킨이 플라스틱 쟁반에 제공됩니다. 와인이 포함되면 플라스틱 잔이 제공됩니다."),
                Map.of("name", "grand", "name_ko", "그랜드", "price_multiplier", 1.3,
                        "description", "도자기 접시와 도자기 컵, 흰색 면 냅킨이 나무 쟁반에 제공됩니다. 와인이 포함되면 플라스틱 잔이 제공됩니다."),
                Map.of("name", "deluxe", "name_ko", "디럭스", "price_multiplier", 1.6,
                        "description", "꽃들이 있는 작은 꽃병, 도자기 접시와 도자기 컵, 린넨 냅킨이 나무 쟁반에 제공됩니다. 와인이 포함되면 유리 잔이 제공됩니다.")
        );
    }
}




