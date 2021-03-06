import {Component, OnInit} from '@angular/core';
import {ActivatedRoute, Router} from '@angular/router';
import {FormBuilder, FormGroup, Validators} from '@angular/forms';
import {AuthService} from '../service/auth.service';
import {AlertService} from '../service/alert.service';
import {Title} from '@angular/platform-browser';
import {environment} from '../../environments/environment';

@Component({
  selector: 'app-login',
  templateUrl: './login.component.html',
  styleUrls: ['./login.component.scss']
})
export class LoginComponent implements OnInit {

  isAuthenticated: boolean;
  loginForm: FormGroup;
  loading = false;
  submitted = false;
  returnUrl: string;

  constructor(
    private formBuilder: FormBuilder,
    private route: ActivatedRoute,
    private router: Router,
    private authService: AuthService,
    private titleService: Title,
    private alertService: AlertService
  ) {
  }

  // convenience getter for easy access to form fields
  get f() {
    return this.loginForm.controls;
  }

  async ngOnInit() {
    // redirect to home if already logged in
    this.isAuthenticated = this.authService.isAuthenticated();
    if (this.isAuthenticated) {
      await this.router.navigate(['/']);
      return;
    }

    this.titleService.setTitle(environment.title);

    this.loginForm = this.formBuilder.group({
      username: ['', Validators.required],
      password: ['', Validators.required]
    });

    // get return url from route parameters or default to '/'
    this.returnUrl = this.route.snapshot.queryParams.returnUrl || '/';
  }

  async onSubmit() {
    this.submitted = true;

    // reset alerts on submit
    this.alertService.clear();

    // stop here if form is invalid
    if (this.loginForm.invalid) {
      return;
    }

    this.loading = true;
    this.authService.login(this.f.username.value, this.f.password.value)
      .subscribe(
        () => {
          this.router.navigate([this.returnUrl]);
        },
        error => {
          console.log('login error');
          console.log(error);
          this.alertService.error(error.message);
          this.loading = false;
        }
      );
  }
}
