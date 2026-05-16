Arama Motoru Servisi
Farklı içerik sağlayıcılardan (provider) gelen verileri birleştirerek, kullanıcının arama sorgusuna göre en uygun
içerikleri bulan, bunları belirli kriterlere göre sıralayan ve sunan bir API geliştirmeni bekliyoruz.
Ekstra: Bu API üzerine basit bir dashboard arayüzü geliştirmen bekleniyor.
Teknik Gereksinimler
API Özellikleri
1. İçerik Arama ve Sıralama
o Anahtar kelimeye göre arama
o İçerik türüne (video/metin) göre filtreleme
o Popülerlik ve alakalılık skoruna göre sıralama
o Sayfalama (pagination)
2. İçerik Puanlama Algoritması
o Provider'dan gelen farklı formatlardaki verileri standart puan sistemine çevirme
o İçerik türüne göre ağırlık katsayıları
o Kullanıcı etkileşimi ve zaman bazlı güncellik puanı
Dashboard
• Basit bir web arayüzü geliştirmeni bekliyoruz.
• Listeleme:
o Başlık
o İçerik türü
o Skor
• Popülerlik/alakalılık skoru ile sıralama
SENARYO
Provider Entegrasyonu
• JSON ve XML formatlarında 2 farklı provider'dan veri alınacak
• İstek limiti yönetimi
• Standart formata dönüşüm
• Yeni provider eklemeye uygun yapı
• Verilerin veritabanında saklanması
Veri Saklama
• Kalıcı veri tutarlığı
• Cache mekanizması önerisi
Teknik Beklentiler
Kod Kalitesi
o Temiz ve anlaşılır kod yapısı
o Hata yönetimi
o Mantıklı test stratejisi
o Performans ve ölçeklenebilirlik
Dokümanlar
o API dokümantasyonu
o Kurulum ve çalıştırma talimatları (README)
o Teknoloji tercih gerekçeleri
Teknoloji Tercihleri
o Backend: Go, PHP (Symfony), .NET Core
o Veritabanı: MySQL, PostgreSQL, MongoDB
Mock API'ler
o Provider 1: JSON
o Provider 2: XML
İçerik Puanlama Formülü
Final Skor = (Temel Puan * İçerik Türü Katsayısı) + Güncellik Puanı + Etkileşim Puanı
Temel Puan:
o Video: views / 1000 + (likes / 100)
o Metin: reading_time + (reactions / 50)
İçerik Türü Katsayısı:
o Video: 1.5
o Metin: 1.0
Güncellik Puanı:
o 1 hafta içinde: +5
o 1 ay içinde: +3
o 3 ay içinde: +1
o Daha eski: +0
Etkileşim Puanı:
o Video: (likes / views) * 10
o Metin: (reactions / reading_time) * 5




Teslim Şekli
o Kodları GitHub üzerinden paylaş. (Lütfen Enuygun adını kullanma.)
o Özelliklerin tamamlanmasından çok, tamamlanan kısmın kaliteli olması bizim için önemli.
o README dosyasında tercih ettiğin dil, mimari kararlar ve kurulum adımları yer almalı.
o Ekstra özellikler ve iyileştirmeler bonus olarak değerlendirilir.
