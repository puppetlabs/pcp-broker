The script 'regen_certs.rb' creates the certs needed by this repo as of July 27th, 2020.
To recreate these certs move the regen_certs.rb and this readme to the root of this repo.
Run it with `ruby regen_certs.rb`, and it will create a new directory 'newcerts' in the root of this repo.
You may then delete 'test-resource/ssl', move 'newcerts' to 'test-resource/ssl' and copy this readme and the regen_certs.rb script to it as well.
