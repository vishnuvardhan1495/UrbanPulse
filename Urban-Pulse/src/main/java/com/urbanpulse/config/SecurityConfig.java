package com.urbanpulse.config;

import com.urbanpulse.security.CustomAccessDeniedHandler;
import com.urbanpulse.security.CustomAuthenticationEntryPoint;
import com.urbanpulse.security.FirebaseAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final FirebaseAuthenticationFilter firebaseAuthenticationFilter;
    private final CustomAuthenticationEntryPoint customAuthenticationEntryPoint;
    private final CustomAccessDeniedHandler customAccessDeniedHandler;

    public SecurityConfig(FirebaseAuthenticationFilter firebaseAuthenticationFilter,
                          CustomAuthenticationEntryPoint customAuthenticationEntryPoint,
                          CustomAccessDeniedHandler customAccessDeniedHandler) {
        this.firebaseAuthenticationFilter = firebaseAuthenticationFilter;
        this.customAuthenticationEntryPoint = customAuthenticationEntryPoint;
        this.customAccessDeniedHandler = customAccessDeniedHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(customAuthenticationEntryPoint)
                        .accessDeniedHandler(customAccessDeniedHandler)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/api/security-test/public", "/uploads/**").permitAll()
                        .requestMatchers("/api/security-test/citizen").hasRole("CITIZEN")
                        .requestMatchers("/api/security-test/worker").hasRole("WORKER")
                        .requestMatchers("/api/security-test/supervisor").hasRole("SUPERVISOR")
                        .requestMatchers("/api/security-test/admin").hasRole("ADMIN")
                        .requestMatchers("/api/admin/complaints/**").hasRole("ADMIN")
                        .requestMatchers("/api/supervisor/complaints/**").hasRole("SUPERVISOR")
                        .requestMatchers("/api/worker/complaints/**").hasRole("WORKER")
                        .requestMatchers(HttpMethod.POST, "/api/users/staff").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/users/workers").hasRole("SUPERVISOR")
                        .requestMatchers(HttpMethod.GET, "/api/users/workers").hasAnyRole("SUPERVISOR", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/users/fcm-token").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/departments/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/departments/**").hasAnyRole("ADMIN", "SUPERVISOR")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(firebaseAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
