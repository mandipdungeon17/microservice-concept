package com.equitycart.user.config;

import com.equitycart.user.dto.RoleSeedData;
import com.equitycart.user.entity.Role;
import com.equitycart.user.repository.RoleRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private static final Logger LOGGER = LogManager.getLogger(DataSeeder.class);

    private final RoleRepository roleRepository;
    private final ObjectMapper objectMapper;

    @Value("classpath:seedData/roles.json")
    Resource rolesFile;

    @Override
    @Transactional
    public void run(String... args) throws Exception {

        List<RoleSeedData> maps = objectMapper.readValue(rolesFile.getInputStream(), new TypeReference<>() {
        });

        for(RoleSeedData seedData : maps) {
            String name = seedData.name();
            if(!roleRepository.existsByName(name)){
                String description = seedData.description();
                Role role = Role.builder().name(name).description(description).build();
                roleRepository.save(role);
                LOGGER.info("Seeded Roles: Name - {} description - {} ", name, description);
            }
        }
    }
}